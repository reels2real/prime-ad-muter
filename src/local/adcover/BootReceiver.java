package local.adcover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start the cover service when the stick boots, so it is ready before the first ad. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, CoverService.class));
        }
    }
}
