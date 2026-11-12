package local.adcover;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/** Invisible: fetches the screen-capture token for ScreenGrabber, then closes. */
public class CaptureActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), 1);
        } catch (Exception e) {
            Log.w(CoverService.TAG, "screen capture not available", e);
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        if (code == RESULT_OK && data != null) ScreenGrabber.onGranted(this, code, data);
        else Log.w(CoverService.TAG, "screen capture not allowed (run: appops set local.adcover PROJECT_MEDIA allow)");
        finish();
        overridePendingTransition(0, 0);
    }
}
