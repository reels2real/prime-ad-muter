package local.adcover;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Only used to take a screenshot while Prime Video is paused, to see whether Prime has put a
 * pause-screen ad up (a still image with a yellow "Ad" pill). Accessibility screenshots need
 * Android 11+, no capture prompt, and run only on request; nothing is read during playback.
 */
public class PauseAdService extends AccessibilityService {
    static volatile PauseAdService instance;
    /** Package of the app in front (from window-state events); used by the badge watch. */
    static volatile String foreground = "";

    interface Result { void done(boolean ad); }

    @Override protected void onServiceConnected() { instance = this; Log.i(CoverService.TAG, "pause-ad checker connected"); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        // our own cover and system popups (volume, toasts) don't change which app is in front
        if (p.equals(getPackageName()) || p.equals("com.android.systemui") || p.equals("android")) return;
        foreground = p;
    }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    interface SkipResult { void done(int[] measure, float scale); }
    interface CountResult { void done(int seconds); }

    /** One screenshot, read for Prime's ad countdown (-1 = none). Main-thread callback. */
    void checkCountdown(final CountResult result) {
        if (Build.VERSION.SDK_INT < 30) { result.done(-1); return; }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult shot) {
                    int secs = -1;
                    HardwareBuffer hb = shot.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            secs = CountdownReader.read(sw);
                            sw.recycle();
                            hw.recycle();
                        }
                    } catch (Exception e) {
                        Log.w(CoverService.TAG, "could not read screenshot", e);
                    } finally {
                        hb.close();
                    }
                    result.done(secs);
                }
                @Override public void onFailure(int code) { result.done(-1); }
            });
        } catch (Exception e) {
            result.done(-1);
        }
    }

    /** One screenshot, measured for YouTube's Skip pill (see SkipDetector). Main thread callback. */
    void checkSkip(final SkipResult result) {
        if (Build.VERSION.SDK_INT < 30) { result.done(new int[] {0, 0}, 1f); return; }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult shot) {
                    int[] m = {0, 0};
                    float scale = 1f;
                    HardwareBuffer hb = shot.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            scale = sw.getWidth() / 1920f;
                            m = SkipDetector.measure(sw);
                            sw.recycle();
                            hw.recycle();
                        }
                    } catch (Exception e) {
                        Log.w(CoverService.TAG, "could not read screenshot", e);
                    } finally {
                        hb.close();
                    }
                    result.done(m, scale);
                }
                @Override public void onFailure(int code) { result.done(new int[] {0, 0}, 1f); }
            });
        } catch (Exception e) {
            result.done(new int[] {0, 0}, 1f);
        }
    }

    /** Press the remote's OK button (Android 13+). YouTube TV focuses its Skip pill when it appears. */
    boolean pressOk() {
        if (Build.VERSION.SDK_INT < 33) return false;
        return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
    }

    /** Take one screenshot and look for the "Ad" pill. Calls back on the main thread. */
    void check(final Result result) {
        if (Build.VERSION.SDK_INT < 30) { result.done(false); return; }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult shot) {
                    boolean ad = false;
                    HardwareBuffer hb = shot.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            ad = BadgeDetector.isAd(sw);
                            sw.recycle();
                            hw.recycle();
                        }
                    } catch (Exception e) {
                        Log.w(CoverService.TAG, "could not read screenshot", e);
                    } finally {
                        hb.close();
                    }
                    result.done(ad);
                }
                @Override public void onFailure(int code) {
                    Log.w(CoverService.TAG, "screenshot failed: " + code);
                    result.done(false);
                }
            });
        } catch (Exception e) {
            Log.w(CoverService.TAG, "screenshot not possible", e);
            result.done(false);
        }
    }
}
