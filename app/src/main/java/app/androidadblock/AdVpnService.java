package app.androidadblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A local VPN that only captures DNS. The tun interface advertises a virtual
 * DNS server and routes nothing but that address, so all other traffic keeps
 * using the normal network. Each lookup is either answered locally (blocked)
 * or relayed to the real upstream resolver.
 */
public class AdVpnService extends VpnService {
    static final String ACTION_STOP = "app.androidadblock.STOP";
    static volatile boolean running;

    private static final String TAG = "AndroidAdBlock";
    private static final String CHANNEL = "status";
    private static final String V4_ADDR = "10.111.222.1";
    private static final String V4_DNS = "10.111.222.2";
    private static final String V6_ADDR = "fd00:ad:51c::1";
    private static final String V6_DNS = "fd00:ad:51c::2";

    private Thread thread;
    /** Identifies the current run, so a thread left over from an earlier run can tell it is stale. */
    private volatile Object session;
    private volatile ParcelFileDescriptor tun;
    private volatile FileOutputStream out;
    private volatile InetAddress[] upstream;
    private ExecutorService pool;

    static void start(Context c) {
        Intent i = new Intent(c, AdVpnService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        if (!running) return;
        c.startService(new Intent(c, AdVpnService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.get(this).edit().putBoolean("enabled", false).apply();
            shutdown();
            return START_NOT_STICKY;
        }
        showNotification();
        if (thread == null) {
            running = true;
            pool = Executors.newFixedThreadPool(16);
            final Object token = new Object();
            session = token;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runVpn(token);
                }
            }, "adblock-vpn");
            thread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        // Another VPN took over, or the user disconnected us from system settings.
        Prefs.get(this).edit().putBoolean("enabled", false).apply();
        shutdown();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private synchronized void shutdown() {
        running = false;
        session = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        closeTun();
        thread = null;
        stopForeground(true);
        stopSelf();
    }

    private synchronized void closeTun() {
        ParcelFileDescriptor t = tun;
        tun = null;
        out = null;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void runVpn(Object token) {
        try {
            Filter.load(this);
            if (BlocklistUpdater.isStale(this)) refreshListsInBackground();

            String[] servers = Prefs.DNS_SERVERS[Prefs.dns(this)];
            upstream = new InetAddress[]{
                    InetAddress.getByName(servers[0]), InetAddress.getByName(servers[1])};

            Builder b = new Builder();
            b.setSession("AndroidAdBlock");
            b.addAddress(V4_ADDR, 32);
            b.addDnsServer(V4_DNS);
            b.addRoute(V4_DNS, 32);
            try {
                b.addAddress(V6_ADDR, 128);
                b.addDnsServer(V6_DNS);
                b.addRoute(V6_DNS, 128);
            } catch (RuntimeException e) {
                Log.w(TAG, "IPv6 not available on the tun interface", e);
            }
            b.setBlocking(true);
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            b.setConfigureIntent(PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));

            ParcelFileDescriptor t = b.establish();
            if (t == null) {
                Log.e(TAG, "VPN permission has not been granted");
                return;
            }
            synchronized (this) {
                if (session != token) {
                    t.close();
                    return;
                }
                tun = t;
                out = new FileOutputStream(t.getFileDescriptor());
            }
            pump(t.getFileDescriptor(), token);
        } catch (Exception e) {
            if (session == token) Log.e(TAG, "VPN loop failed", e);
        } finally {
            if (session == token) shutdown();
        }
    }

    /** Reads packets from the tun device until the service stops. */
    private void pump(FileDescriptor fd, Object token) throws IOException {
        FileInputStream in = new FileInputStream(fd);
        byte[] buf = new byte[32767];
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = {p};
        while (session == token) {
            // Poll with a timeout so a stop request is noticed even when no packets arrive.
            try {
                p.revents = 0;
                if (Os.poll(fds, 1000) == 0) continue;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) continue;
                throw new IOException(e);
            }
            if ((p.revents & OsConstants.POLLIN) == 0) {
                if (p.revents != 0) return;          // error or hang-up: the interface is gone
                continue;
            }
            int n = in.read(buf);
            if (n <= 0) continue;
            handle(Arrays.copyOf(buf, n));
        }
    }

    private void handle(byte[] packet) {
        final Packets.Udp req = Packets.parse(packet, packet.length);
        if (req == null || req.dstPort != 53) return;
        DnsMessage.Question q = DnsMessage.parse(req.payload);
        if (q != null) {
            boolean block = Filter.isBlocked(q.name);
            Stats.add(q.name, block);
            if (block) {
                write(Packets.reply(req, DnsMessage.blocked(req.payload, q)));
                return;
            }
        }
        ExecutorService ex = pool;
        if (ex == null) return;
        try {
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    forward(req);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    /** Relays a query to the upstream resolver and writes the answer back to the tun device. */
    private void forward(Packets.Udp req) {
        InetAddress[] servers = upstream;
        if (servers == null) return;
        for (InetAddress server : servers) {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                protect(s);
                s.setSoTimeout(4000);
                s.send(new DatagramPacket(req.payload, req.payload.length, server, 53));
                byte[] buf = new byte[16384];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                s.receive(resp);
                write(Packets.reply(req, Arrays.copyOf(buf, resp.getLength())));
                return;
            } catch (IOException e) {
                // Timed out or unreachable: try the next server.
            } finally {
                if (s != null) s.close();
            }
        }
    }

    private void write(byte[] packet) {
        FileOutputStream o = out;
        if (o == null) return;
        try {
            synchronized (o) {
                o.write(packet);
            }
        } catch (IOException ignored) {
            // The interface was closed while a reply was in flight.
        }
    }

    private void refreshListsInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Give the tunnel a moment to come up; our own lookup goes through it.
                    Thread.sleep(3000);
                    BlocklistUpdater.update(AdVpnService.this);
                } catch (Exception e) {
                    Log.w(TAG, "Blocklist update failed", e);
                }
            }
        }, "adblock-update").start();
    }

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Blocking status", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AdVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = nb.setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("AndroidAdBlock is blocking ads")
                .setContentText("Tap to open")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (RuntimeException e) {
            // The VPN itself keeps the service bound; carry on without the notification.
            Log.w(TAG, "Could not enter the foreground", e);
        }
    }
}
