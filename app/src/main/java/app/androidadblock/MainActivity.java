package app.androidadblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 20, 24);
    private static final int CARD = Color.rgb(28, 34, 40);
    private static final int TEXT = Color.rgb(230, 233, 236);
    private static final int DIM = Color.rgb(140, 150, 160);
    private static final int GREEN = Color.rgb(46, 204, 113);
    private static final int RED = Color.rgb(231, 96, 80);
    private static final String TEST_DOMAIN = "doubleclick.net";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView stats;
    private TextView diag;
    private volatile String testResult = "";
    private volatile boolean testPassed;
    private int ticks;
    private TextView listInfo;
    private Button toggle;
    private Button update;
    private Button dns;
    private LinearLayout log;
    private int shownVersion = -1;
    private boolean updating;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("AndroidAdBlock", 28, TEXT, true));
        status = text("", 15, DIM, false);
        root.addView(status);

        toggle = button("");
        toggle.setTextSize(18);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });
        root.addView(toggle, block(dp(64), dp(16)));

        diag = text("", 15, DIM, false);
        root.addView(diag, block(ViewGroup.LayoutParams.WRAP_CONTENT, dp(12)));

        stats = text("", 16, TEXT, false);
        root.addView(stats, block(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16)));

        root.addView(heading("Blocklists"));
        listInfo = text("", 14, DIM, false);
        root.addView(listInfo);
        update = button("Update blocklists");
        update.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateLists();
            }
        });
        root.addView(update, block(dp(48), dp(8)));

        root.addView(heading("Settings"));
        dns = button("");
        dns.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDns();
            }
        });
        root.addView(dns, block(dp(48), dp(8)));

        Button allowed = button("Allowed domains");
        allowed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRules(Filter.ALLOW, "Allowed domains");
            }
        });
        root.addView(allowed, block(dp(48), dp(8)));

        Button denied = button("Extra blocked domains");
        denied.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRules(Filter.DENY, "Extra blocked domains");
            }
        });
        root.addView(denied, block(dp(48), dp(8)));

        CheckBox boot = new CheckBox(this);
        boot.setText("Turn on automatically after a restart");
        boot.setTextColor(TEXT);
        boot.setChecked(Prefs.get(this).getBoolean("boot", true));
        boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).edit().putBoolean("boot", checked).apply();
            }
        });
        root.addView(boot, block(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)));

        root.addView(heading("Recent lookups"));
        root.addView(text("Tap a name to allow or block it.", 13, DIM, false));
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        root.addView(log, block(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);

        Filter.loadRules(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownVersion = -1;
        ticks = 0;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void onToggle() {
        if (AdVpnService.running) {
            AdVpnService.stop(this);
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, 1);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1) return;
        if (resultCode == RESULT_OK) {
            startVpn();
        } else {
            toast("VPN permission is needed to filter ads");
        }
    }

    private void startVpn() {
        Prefs.get(this).edit().putBoolean("enabled", true).apply();
        AdVpnService.start(this);
    }

    private void restartIfRunning() {
        if (!AdVpnService.running) return;
        AdVpnService.stop(this);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (VpnService.prepare(MainActivity.this) == null) startVpn();
            }
        }, 1500);
    }

    private void refresh() {
        boolean on = AdVpnService.running;
        status.setText(on ? "Protection is on. Ads and trackers are blocked in all apps."
                : "Protection is off.");
        toggle.setText(on ? "Turn off" : "Turn on");
        toggle.setBackgroundColor(on ? CARD : GREEN);
        toggle.setTextColor(on ? TEXT : Color.BLACK);
        stats.setText("Blocked " + Stats.blocked.get() + " of " + Stats.total.get()
                + " lookups since the app started");

        // Re-run the self-test shortly after opening and then every ten seconds.
        if (on && ticks % 10 == 2) runSelfTest();
        ticks++;
        String pdns = privateDnsHost();
        if (!on) {
            testResult = "";
            diag.setText("");
        } else if (pdns != null) {
            diag.setTextColor(RED);
            diag.setText("Not blocking: Android's Private DNS is set to \"" + pdns + "\", which bypasses "
                    + "this app. Open Settings, search for \"Private DNS\", and set it to Off.");
        } else {
            diag.setTextColor(testResult.isEmpty() ? DIM : testPassed ? GREEN : RED);
            diag.setText(testResult.isEmpty() ? "Checking that blocking works…" : testResult);
        }

        long at = Prefs.get(this).getLong("listUpdated", 0);
        if (updating) {
            listInfo.setText("Downloading…");
        } else if (at == 0) {
            listInfo.setText("Using the small built-in list. The full lists download "
                    + "the first time protection is turned on.");
        } else {
            String n = Filter.size() > 0 ? Filter.size() + " domains · " : "";
            listInfo.setText(n + "updated " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(at)));
        }
        update.setEnabled(!updating);
        dns.setText("DNS server: " + Prefs.DNS_NAMES[Prefs.dns(this)]);

        if (shownVersion != Stats.version) {
            shownVersion = Stats.version;
            renderLog();
        }
    }

    /** Returns the Private DNS provider hostname if one is forced on, otherwise null. */
    private String privateDnsHost() {
        try {
            String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            if ("hostname".equals(mode)) {
                String host = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
                return host == null || host.isEmpty() ? "a custom provider" : host;
            }
        } catch (RuntimeException ignored) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                for (Network n : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null && lp.getPrivateDnsServerName() != null) return lp.getPrivateDnsServerName();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** Looks up a known ad domain the way any app would and reports whether it came back blocked. */
    private void runSelfTest() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                boolean pass = false;
                try {
                    InetAddress[] all = InetAddress.getAllByName(TEST_DOMAIN);
                    pass = all.length > 0;
                    for (InetAddress a : all) pass &= a.isAnyLocalAddress();
                    msg = pass ? "Self-test passed: " + TEST_DOMAIN + " is blocked on this phone."
                            : "Self-test FAILED: " + TEST_DOMAIN + " still resolves to "
                            + all[0].getHostAddress() + ", so lookups are not going through this app.";
                } catch (Exception e) {
                    msg = "Self-test could not run: no answer for " + TEST_DOMAIN
                            + ". Check the internet connection.";
                }
                testPassed = pass;
                testResult = msg;
            }
        }, "adblock-selftest").start();
    }

    private void renderLog() {
        log.removeAllViews();
        List<Stats.Entry> entries = Stats.snapshot();
        if (entries.isEmpty()) {
            log.addView(text("Nothing yet.", 14, DIM, false));
            return;
        }
        for (final Stats.Entry e : entries) {
            TextView t = text((e.blocked ? "✕  " : "✓  ") + e.name, 14, e.blocked ? RED : TEXT, false);
            t.setPadding(0, dp(7), 0, dp(7));
            t.setSingleLine(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ruleDialog(e);
                }
            });
            log.addView(t);
        }
    }

    private void ruleDialog(final Stats.Entry e) {
        final String key = e.blocked ? Filter.ALLOW : Filter.DENY;
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(e.blocked ? "This lookup was blocked. Allow this domain from now on?"
                        : "This lookup was allowed. Block this domain from now on?")
                .setPositiveButton(e.blocked ? "Allow" : "Block", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        // Drop any opposite rule so the new one takes effect.
                        String other = e.blocked ? Filter.DENY : Filter.ALLOW;
                        Set<String> o = Prefs.domains(MainActivity.this, other);
                        o.remove(e.name);
                        Prefs.setDomains(MainActivity.this, other, o);
                        Set<String> s = Prefs.domains(MainActivity.this, key);
                        s.add(e.name);
                        Prefs.setDomains(MainActivity.this, key, s);
                        Filter.loadRules(MainActivity.this);
                        toast("Saved. Apps may take a minute to notice.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editRules(final String key, String title) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);
        input.setHint("one domain per line");
        StringBuilder sb = new StringBuilder();
        for (String d : Prefs.domains(this, key)) sb.append(d).append('\n');
        input.setText(sb.toString());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Set<String> s = new TreeSet<>();
                        for (String line : input.getText().toString().split("\n")) {
                            String clean = Blocklist.cleanDomain(line);
                            if (clean != null) s.add(clean);
                        }
                        Prefs.setDomains(MainActivity.this, key, s);
                        Filter.loadRules(MainActivity.this);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickDns() {
        new AlertDialog.Builder(this)
                .setTitle("DNS server")
                .setSingleChoiceItems(Prefs.DNS_NAMES, Prefs.dns(this), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Prefs.get(MainActivity.this).edit().putInt("dns", which).apply();
                        d.dismiss();
                        restartIfRunning();
                    }
                })
                .show();
    }

    private void updateLists() {
        updating = true;
        refresh();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    msg = "Blocklists updated: " + BlocklistUpdater.update(getApplicationContext()) + " domains";
                } catch (Exception e) {
                    msg = "Update failed. Check your connection and try again.";
                }
                final String m = msg;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        updating = false;
                        toast(m);
                        refresh();
                    }
                });
            }
        }, "adblock-update").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 13, GREEN, true);
        t.setAllCaps(true);
        t.setPadding(0, dp(28), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackgroundColor(CARD);
        return b;
    }

    private LinearLayout.LayoutParams block(int height, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = topMargin;
        return lp;
    }
}
