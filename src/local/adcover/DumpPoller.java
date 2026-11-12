package local.adcover;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback for low-RAM devices (Mi TV Stick: ro.config.low_ram=true), where Android silently
 * refuses notification-listener access, so SessionListener never connects.
 *
 * With android.permission.DUMP (granted once over ADB: `pm grant local.adcover android.permission.DUMP`)
 * the app can read the media_session service's dump, the same text `dumpsys media_session` prints,
 * and pull Prime's PlaybackState state + actions out of it. The dump is requested in-process
 * through the service's binder (no shell process per poll); `dumpsys` is the fallback.
 *
 * Also reads YouTube's session the same way (YouTube TV ads: playing with no seeking).
 * Idle while the real listener is connected. Polls every 400 ms while Prime or YouTube has a session,
 * every 2 s otherwise. Only reports changes.
 */
final class DumpPoller {
    static final String TAG = "AdCover";
    static final String DUMP = "android.permission.DUMP";
    static final long FAST_MS = 400, SLOW_MS = 2000;

    // "state=PlaybackState {state=3, position=..., speed=1.0, updated=..., actions=1049455, ..."
    private static final Pattern PLAYBACK =
            Pattern.compile("state=PlaybackState \\{state=(\\d+),.*?actions=(\\d+)");

    private static HandlerThread thread;
    private static Handler handler;
    private static volatile boolean running, sawPrime;
    private static volatile String lastKey = "";
    private static volatile String method = "none";
    private static volatile long polls;

    private DumpPoller() { }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    static String describe(Context c) {
        if (!permitted(c)) return "no-permission";
        if (SessionListener.connected) return "idle(listener)";
        return (running ? "polling" : "stopped") + "/" + method + (sawPrime ? "/prime" : "/no-prime")
                + "/n=" + polls;
    }

    static synchronized void start(final Context ctx) {
        if (running) return;
        final Context c = ctx.getApplicationContext();
        running = true;
        thread = new HandlerThread("adcover-dump");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            public void run() {
                if (!running) return;
                long next = SLOW_MS;
                if (!SessionListener.connected && permitted(c)) {
                    try {
                        next = poll(c) ? FAST_MS : SLOW_MS;
                    } catch (Throwable t) {
                        Log.w(TAG, "dump poll failed", t);
                    }
                }
                if (running) handler.postDelayed(this, next);
            }
        });
        Log.i(TAG, "dump poller started (permission=" + permitted(c) + ")");
    }

    static synchronized void stop() {
        running = false;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    private static volatile String lastYtKey = "";

    /** state + actions of one app's session in the dump, or null if it has none. */
    private static long[] section(String text, String pkg) {
        int at = text.indexOf("package=" + pkg);
        if (at < 0) return null;
        int end = text.indexOf("package=", at + 8);
        Matcher m = PLAYBACK.matcher(end < 0 ? text.substring(at) : text.substring(at, end));
        if (!m.find()) return null;
        return new long[] {Integer.parseInt(m.group(1)), Long.parseLong(m.group(2))};
    }

    /** One poll; returns true if Prime or YouTube currently has a session (poll fast). */
    private static boolean poll(final Context c) throws Exception {
        String text = dump(c);
        polls++;
        long[] yt = section(text, SessionListener.YOUTUBE);
        final int yst = yt == null ? PlaybackState.STATE_NONE : (int) yt[0];
        final long yact = yt == null ? 0 : yt[1];
        String ykey = yst + "/" + yact;
        if (!ykey.equals(lastYtKey)) {
            lastYtKey = ykey;
            Log.i(TAG, "youtube session (dump): state=" + yst + " actions=" + yact);
            new Handler(c.getMainLooper()).post(new Runnable() {
                public void run() { SessionListener.applyYouTube(yst, yact); }
            });
        }
        int st = PlaybackState.STATE_NONE;
        long actions = 0;
        boolean found = false;
        int at = text.indexOf("package=" + SessionListener.PRIME);
        if (at >= 0) {
            int end = text.indexOf("package=", at + 8);
            String section = end < 0 ? text.substring(at) : text.substring(at, end);
            Matcher m = PLAYBACK.matcher(section);
            if (m.find()) {
                st = Integer.parseInt(m.group(1));
                actions = Long.parseLong(m.group(2));
                found = true;
            }
        }
        sawPrime = found;
        String key = st + "/" + actions;
        if (!key.equals(lastKey)) {
            lastKey = key;
            Log.i(TAG, "prime session (dump): state=" + st + " actions=" + actions);
            final int fst = st;
            final long fact = actions;
            new Handler(c.getMainLooper()).post(new Runnable() {
                public void run() { SessionListener.apply(c, fst, fact); }
            });
        }
        return found || yt != null;
    }

    private static String dump(Context c) throws Exception {
        File f = new File(c.getCacheDir(), "media_session.txt");
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "media_session");
            if (b != null) {
                FileOutputStream out = new FileOutputStream(f);
                try { b.dump(out.getFD(), new String[0]); } finally { out.close(); }
                method = "binder";
                return read(new FileInputStream(f));
            }
        } catch (Throwable t) {
            if (!"dumpsys".equals(method)) Log.w(TAG, "binder dump unavailable, using dumpsys", t);
        }
        method = "dumpsys";
        Process p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
        try { return read(p.getInputStream()); } finally { p.destroy(); }
    }

    private static String read(InputStream in) throws Exception {
        try {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            return sb.toString();
        } finally {
            in.close();
        }
    }
}
