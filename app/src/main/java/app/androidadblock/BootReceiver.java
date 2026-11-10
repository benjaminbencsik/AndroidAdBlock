package app.androidadblock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Turns blocking back on after a reboot if it was on before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Prefs.get(context).getBoolean("enabled", false)) return;
        if (!Prefs.get(context).getBoolean("boot", true)) return;
        // prepare() returns null only when the user has already granted VPN consent.
        if (VpnService.prepare(context) != null) return;
        AdVpnService.start(context);
    }
}
