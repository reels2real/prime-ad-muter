package local.adcover;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Pause-ad screenshots for Android 8-10, where accessibility screenshots don't exist.
 * Uses screen capture (MediaProjection). The consent is granted once over ADB with
 * `appops set local.adcover PROJECT_MEDIA allow`, so the system grants it without a prompt;
 * CaptureActivity picks the token up invisibly when the service starts. Nothing is captured
 * during playback: a virtual display is created for one frame while paused, then released.
 */
class ScreenGrabber {
    static volatile MediaProjection projection;
    private static HandlerThread thread;

    static boolean needed() { return Build.VERSION.SDK_INT < 30; }

    static boolean ready() { return projection != null; }

    /** Ask for the capture token (instant and invisible once PROJECT_MEDIA is allowed). */
    static void request(Context c) {
        if (!needed() || projection != null) return;
        try {
            Intent i = new Intent(c, CaptureActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(CoverService.TAG, "could not ask for screen capture", e);
        }
    }

    static void onGranted(Context c, int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection p = mpm.getMediaProjection(resultCode, data);
        if (p == null) { Log.w(CoverService.TAG, "screen capture refused"); return; }
        p.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { projection = null; Log.i(CoverService.TAG, "screen capture stopped"); }
        }, new Handler(Looper.getMainLooper()));
        projection = p;
        Log.i(CoverService.TAG, "pause-ad checker ready (screen capture)");
    }

    /** One frame, measured for YouTube's Skip pill. Calls back on the main thread. */
    static void checkSkip(Context c, final PauseAdService.SkipResult result) {
        final int[][] m = {{0, 0}};
        final float[] scale = {1f};
        grab(c, new Analyzer() {
            public boolean analyze(Bitmap bmp) {
                scale[0] = bmp.getWidth() / 1920f;
                m[0] = SkipDetector.measure(bmp);
                return false;
            }
        }, new PauseAdService.Result() {
            public void done(boolean ignored) { result.done(m[0], scale[0]); }
        });
    }

    /** One frame, read for Prime's ad countdown (-1 = none). Calls back on the main thread. */
    static void checkCountdown(Context c, final PauseAdService.CountResult result) {
        final int[] secs = {-1};
        grab(c, new Analyzer() {
            public boolean analyze(Bitmap bmp) { secs[0] = CountdownReader.read(bmp); return false; }
        }, new PauseAdService.Result() {
            public void done(boolean ignored) { result.done(secs[0]); }
        });
    }

    interface Analyzer { boolean analyze(Bitmap bmp); }

    /** One frame, checked for the "Ad" pill. Calls back on the main thread. */
    static void check(Context c, final PauseAdService.Result result) {
        grab(c, new Analyzer() {
            public boolean analyze(Bitmap bmp) { return BadgeDetector.isAd(bmp); }
        }, result);
    }

    private static void grab(Context c, final Analyzer analyzer, final PauseAdService.Result result) {
        final Handler main = new Handler(Looper.getMainLooper());
        final MediaProjection p = projection;
        if (p == null) { result.done(false); return; }
        if (thread == null) { thread = new HandlerThread("adcover-grab"); thread.start(); }
        final Handler bg = new Handler(thread.getLooper());

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        final int w = dm.widthPixels, h = dm.heightPixels;
        final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        final VirtualDisplay[] vd = new VirtualDisplay[1];
        final boolean[] finished = new boolean[1];

        final Object lock = new Object();

        class Done {
            void finish(final boolean ad) {
                synchronized (lock) {
                    if (finished[0]) return;
                    finished[0] = true;
                }
                try { if (vd[0] != null) vd[0].release(); } catch (Exception ignored) { }
                try { reader.close(); } catch (Exception ignored) { }
                main.post(new Runnable() { public void run() { result.done(ad); } });
            }
        }
        final Done done = new Done();

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image img = null;
                boolean ad = false;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    Image.Plane plane = img.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    int pxStride = plane.getPixelStride(), rowStride = plane.getRowStride();
                    Bitmap padded = Bitmap.createBitmap(rowStride / pxStride, h, Bitmap.Config.ARGB_8888);
                    padded.copyPixelsFromBuffer(buf);
                    Bitmap bmp = padded.getWidth() == w ? padded : Bitmap.createBitmap(padded, 0, 0, w, h);
                    ad = analyzer.analyze(bmp);
                    if (bmp != padded) bmp.recycle();
                    padded.recycle();
                } catch (Exception e) {
                    Log.w(CoverService.TAG, "could not read screen capture", e);
                } finally {
                    if (img != null) img.close();
                }
                done.finish(ad);
            }
        }, bg);
        try {
            vd[0] = p.createVirtualDisplay("adcover-pause-check", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
        } catch (Exception e) {
            Log.w(CoverService.TAG, "screen capture failed", e);
            projection = null;
            done.finish(false);
            return;
        }
        bg.postDelayed(new Runnable() { public void run() { done.finish(false); } }, 2000);
    }
}
