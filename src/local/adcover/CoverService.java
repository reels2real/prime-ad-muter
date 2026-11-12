package local.adcover;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Draws a dark layer over the whole screen while an ad plays.
 *
 * Commands arrive as one line on 127.0.0.1:8765 (sent by the watcher through adb, e.g.
 * `echo 'show 85' | nc 127.0.0.1 8765`):
 *   show [percent]  cover the screen at that darkness (default 85), keep it for 6s
 *   hide            remove the cover
 *   ping            reply "ok shown" / "ok hidden"
 *   mute / unmute   mute or unmute Android's music stream; reply "ok muted" / "ok unmuted"
 *   muted           reply the music stream's mute state
 *   auto on|off     self-contained mode (no Pi): watch Prime's media session, mute + cover video
 *                   ads, check for pause-screen ads with one screenshot while paused. Saved.
 *   status          reply auto / video ad / pause ad / our mute
 *   darkness N      cover opacity for auto mode, 10-100 (saved; default 98)
 *
 * Safety: the cover removes itself if no "show" arrives for 6 seconds, so it can never
 * get stuck if the watcher stops. It never takes focus or input, so the remote keeps
 * working, and it leaves the top-right corner clear so the "Ad 0:19" badge stays visible
 * (the watcher needs to see it, and so do you).
 */
public class CoverService extends Service {
    static final String TAG = "AdCover";
    static final int PORT = 8765;
    static final long TIMEOUT_MS = 6000;
    // badge corner on a 1920x1080 screen, scaled to the real size at draw time
    static final float HOLE_L = 1640f / 1920, HOLE_T = 50f / 1080, HOLE_R = 1870f / 1920, HOLE_B = 150f / 1080;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CoverView view;
    private volatile boolean shown;
    private volatile long lastShow;
    private Thread server;

    // ---- auto mode (all in the app; see SessionListener, PauseAdService) ----
    static volatile CoverService instance;
    static final long MUTE_CAP_MS = 5 * 60 * 1000;   // never hold a mute longer than this
    static final long PAUSE_AD_FIRST_CHECK_MS = 5000; // Prime shows its pause ad ~5-7 s after pausing
    static final long PAUSE_AD_CHECK_EVERY_MS = 3000;
    static final long PAUSE_AD_GIVE_UP_MS = 60000;
    private android.content.SharedPreferences prefs;
    private boolean auto;
    private boolean videoAd;          // Prime is playing a video ad right now
    private boolean weMuted;          // the current mute is ours (saved, survives restarts)
    private boolean userOverride;     // you unmuted during this ad: stay out of it until it ends
    private long videoAdSince;
    private boolean paused;
    private long pausedSince;
    private boolean pauseAd;          // a pause-screen ad is on screen
    private long nextPauseCheck;
    private boolean checking;
    private long startedAt;

    // ---- Prime's ad countdown ("Ad 1:26"): read from a screenshot ~1 s into an ad, shown on the
    // cover, and used to hold the mute if Prime's status says "ad over" with time still left
    // (e.g. a glitch at the hand-over between two ads of one break).
    static final long HOLD_MIN_LEFT_MS = 3000;      // hold only if more than this is left
    static final long HOLD_CHECK_MS = 2000;
    private long breakEndsAt;                        // 0 = countdown unknown
    private long nextCountdownRead;
    private int countdownTries;
    private boolean cdReading;
    private boolean holding;                         // status said over, countdown says not yet
    private long lastCountdownRead;
    static final long RESTORE_GIVE_UP_MS = 10000;   // no reading after a restart: release our mute

    // ---- badge watch: for devices where Prime's media session says nothing (Mi TV Box 4S,
    // Android 14 / Google TV: the session stays at PLAYING, position 0, through ads and movie).
    // Only while Prime is in front AND audio is playing: one accessibility screenshot a second,
    // and only the top-right corner is examined. A miss is confirmed by a second quick look
    // before unmuting. Pauses are detected by the audio stopping.
    static final String PRIME = "com.amazon.amazonvideo.livingroom";
    static final long WATCH_CONFIRM_MS = 400;
    private boolean watch;
    private boolean watchChecking;
    private int watchMisses;
    private long quietSince;          // when Prime's audio went quiet (0 = playing)

    // ---- YouTube: ad from its media session; Skip pressed with the OK key once it's allowed.
    private boolean ytAd;
    private boolean ytEnding;         // ad just ended; waiting YT_GAP_MS in case another follows
    static final long YT_GAP_MS = 800; // back-to-back YouTube ads are ~0.4-0.5 s apart
    private final Runnable ytEnd = new Runnable() {
        public void run() {
            ytEnding = false;
            if (ytAd) return;
            endVideoAd("YouTube ad over");
            youtubeHole = false;
        }
    };
    private int skipPresses;
    private long nextSkipCheck;
    private boolean skipChecking;
    /** Which corner the cover leaves clear: false = top-right (Prime's badge), true = YouTube's Skip pill. */
    static volatile boolean youtubeHole;
    /** "plain" cover: just "AD PLAYING" (no clock, date or quotes). Saved pref "style". */
    static volatile boolean plainStyle;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences("adcover", MODE_PRIVATE);
        auto = prefs.getBoolean("auto", false);
        weMuted = prefs.getBoolean("weMuted", false);
        watch = prefs.getBoolean("watch", false);
        plainStyle = "plain".equals(prefs.getString("style", "full"));
        // weMuted from a previous run is resolved by the first onPrimeState: keep it if an ad is
        // still playing, undo it if not.
        instance = this;
        startForegroundQuietly();
        server = new Thread(new Runnable() { public void run() { serve(); } }, "adcover-server");
        server.setDaemon(true);
        server.start();
        main.postDelayed(watchdog, 1000);
        startedAt = System.currentTimeMillis();
        if (weMuted && auto) {
            // Restarted while our mute was on (e.g. the 1 GB stick killed us mid-ad): keep the
            // sound off and the cover up until a real reading says whether the ad is still on.
            // Deciding on the default "no ad" here let ~0.5 s of ad audio through on each restart.
            if (!isMuted()) setMuted(true);
            main.post(new Runnable() { public void run() { show(Math.round(darkness() * 255 / 100f), -1); } });
            Log.i(TAG, "restarted with our mute on: holding mute + cover until the first reading");
        }
        if (SessionListener.haveReading) {
            main.post(new Runnable() { public void run() { onPrimeState(SessionListener.lastVideoAd, SessionListener.lastPaused); } });
        }
        if (auto) ScreenGrabber.request(this);   // Android 8-10: get the capture token now, not mid-show
        DumpPoller.start(this);                  // low-RAM sticks: no notification listener allowed
        Log.i(TAG, "started, auto=" + auto + " watch=" + watch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundQuietly() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("adcover", "Ad Cover", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "adcover")
                .setContentTitle("Ad Cover")
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .build();
        startForeground(1, n);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            autoTick();
            boolean restoring = weMuted && !SessionListener.haveReading;
            if (shown && !videoAd && !pauseAd && !restoring && System.currentTimeMillis() - lastShow > TIMEOUT_MS) {
                Log.i(TAG, "no show command for " + TIMEOUT_MS + "ms, hiding");
                hide();
            }
            main.postDelayed(this, 1000);
        }
    };

    private void serve() {
        while (true) {
            try (ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
                while (true) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(2000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                        String line = in.readLine();
                        String reply = handle(line == null ? "" : line.trim());
                        OutputStream out = s.getOutputStream();
                        out.write((reply + "\n").getBytes());
                        out.flush();
                    } catch (Exception e) {
                        Log.w(TAG, "client error", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "server error, retrying", e);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private String handle(String cmd) {
        if (cmd.startsWith("show")) {
            int pct = 92;
            int secs = -1;
            String[] p = cmd.split("\\s+");
            if (p.length > 1) {
                try { pct = Math.max(10, Math.min(100, Integer.parseInt(p[1]))); } catch (NumberFormatException ignored) { }
            }
            if (p.length > 2) {
                try { secs = Integer.parseInt(p[2]); } catch (NumberFormatException ignored) { }
            }
            lastShow = System.currentTimeMillis();
            final int alpha = Math.round(pct * 255 / 100f);
            final int seconds = secs;
            main.post(new Runnable() { public void run() { show(alpha, seconds); } });
            return "ok shown";
        } else if (cmd.equals("hide")) {
            main.post(new Runnable() { public void run() { hide(); } });
            return "ok hidden";
        } else if (cmd.equals("ping")) {
            return shown ? "ok shown" : "ok hidden";
        } else if (cmd.equals("mute") || cmd.equals("unmute")) {
            // Android-side mute of the music stream. On the BenQ QS01 the remote's mute key goes to
            // the projector instead (a blind toggle), so this keeps our mute separate and readable.
            boolean want = cmd.equals("mute");
            try {
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        want ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            } catch (Exception e) {
                Log.e(TAG, "could not " + cmd, e);
                return "error " + e.getClass().getSimpleName();
            }
            return audio().isStreamMute(AudioManager.STREAM_MUSIC) ? "ok muted" : "ok unmuted";
        } else if (cmd.equals("auto on") || cmd.equals("auto off")) {
            final boolean on = cmd.equals("auto on");
            main.post(new Runnable() { public void run() { setAuto(on); } });
            return on ? "ok auto on" : "ok auto off";
        } else if (cmd.equals("style plain") || cmd.equals("style full")) {
            plainStyle = cmd.equals("style plain");
            prefs.edit().putString("style", plainStyle ? "plain" : "full").apply();
            main.post(new Runnable() { public void run() { if (view != null) view.invalidate(); } });
            return "ok style " + (plainStyle ? "plain" : "full");
        } else if (cmd.equals("watch on") || cmd.equals("watch off")) {
            final boolean on = cmd.equals("watch on");
            main.post(new Runnable() { public void run() {
                watch = on;
                prefs.edit().putBoolean("watch", on).apply();
                Log.i(TAG, "badge watch " + (on ? "on" : "off"));
                if (!on) { endVideoAd("watch off"); endPauseAd(); }
            } });
            return on ? "ok watch on" : "ok watch off";
        } else if (cmd.equals("skip done")) {
            if (skipWantedAt != 0) Log.i(TAG, "the Pi pressed OK");
            skipWantedAt = 0;
            return "ok";
        } else if (cmd.startsWith("darkness ")) {
            try {
                final int d = Math.max(10, Math.min(100, Integer.parseInt(cmd.substring(9).trim())));
                prefs.edit().putInt("darkness", d).apply();
                main.post(new Runnable() { public void run() {
                    if (shown) view.setAlpha255(Math.round(d * 255 / 100f));
                } });
                return "ok darkness " + d;
            } catch (NumberFormatException e) {
                return "error darkness needs a number";
            }
        } else if (cmd.equals("status")) {
            return "ok auto=" + auto + " style=" + (plainStyle ? "plain" : "full") + " watch=" + watch + " fg=" + PauseAdService.foreground + " videoAd=" + videoAd + " pauseAd=" + pauseAd + " paused=" + paused
                    + " darkness=" + darkness() + " weMuted=" + weMuted + " override=" + userOverride
                    + " listener=" + SessionListener.connected
                    + " dump=" + DumpPoller.describe(this)
                    + " screenshots=" + (ScreenGrabber.needed()
                        ? (ScreenGrabber.ready() ? "capture" : "no-capture") : (PauseAdService.instance != null ? "a11y" : "no-a11y"))
                    + " yt=" + ytAd
                    + " skip=" + (ytAd && skipWantedAt != 0 && System.currentTimeMillis() - skipWantedAt < 3000 ? "wanted" : "no")
                    + " sdk=" + android.os.Build.VERSION.SDK_INT;
        } else if (cmd.equals("muted")) {
            return audio().isStreamMute(AudioManager.STREAM_MUSIC) ? "ok muted" : "ok unmuted";
        }
        return "error unknown command";
    }

    // ---------------------------------------------------------------- auto mode

    private void setAuto(boolean on) {
        auto = on;
        prefs.edit().putBoolean("auto", on).apply();
        Log.i(TAG, "auto " + (on ? "on" : "off"));
        if (!on) {
            ytAd = false;
            ytEnding = false;
            main.removeCallbacks(ytEnd);
            youtubeHole = false;
            endVideoAd("auto off");
            endPauseAd();
        } else {
            ScreenGrabber.request(this);
            onPrimeState(SessionListener.lastVideoAd, SessionListener.lastPaused);
        }
    }

    /** Called (main thread) whenever Prime's playback state changes. */
    void onPrimeState(boolean ad, boolean isPaused) {
        if (ytAd || ytEnding) return;    // a YouTube ad owns the cover right now
        if (watch) return;   // the badge watch decides; this session can't be trusted here
        if (isPaused && !paused) {
            pausedSince = System.currentTimeMillis();
            nextPauseCheck = pausedSince + PAUSE_AD_FIRST_CHECK_MS;
        }
        paused = isPaused;
        if (!paused) endPauseAd();
        if (!auto && !weMuted) return;
        if (ad && auto) {
            startVideoAd();
        } else if (!ad) {
            if (videoAd) {
                endVideoAd("ad over");
            } else if (weMuted) {           // left over from before a restart, and no ad now
                if (isMuted()) setMuted(false);
                setWeMuted(false);
                if (!pauseAd) hide();
                Log.i(TAG, "UNMUTED (our mute from before a restart; no ad now)");
            }
        }
    }

    /** Called (main thread) when YouTube's playback state changes. */
    void onYouTubeState(final boolean ad) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() { public void run() { onYouTubeState(ad); } });
            return;
        }
        if (ad == ytAd) return;
        if (ad) {
            if (!auto) return;
            if (ytEnding) {           // the next ad of a break: keep the mute and cover on
                main.removeCallbacks(ytEnd);
                ytEnding = false;
                Log.i(TAG, "next YouTube ad, still muted");
            }
            ytAd = true;
            youtubeHole = true;
            skipPresses = 0;
            skipWantedAt = 0;
            // YouTube shows its Skip pill 5-6 s into an ad; no point looking earlier
            nextSkipCheck = System.currentTimeMillis() + 4000;
            Log.i(TAG, "YouTube ad");
            if (view != null) view.invalidate();
            startVideoAd();
        } else {
            ytAd = false;
            skipWantedAt = 0;
            ytEnding = true;
            main.postDelayed(ytEnd, YT_GAP_MS);
        }
    }

    /**
     * Android 9-12 (Mi Stick) can't press keys from an app. There the app only raises a flag,
     * "skip=wanted" in `status`, and the Raspberry Pi (which already has ADB access) reads it and
     * presses OK. No Pi: the ad plays out, muted and covered. Cleared by `skip done` or the ad ending.
     */
    private volatile long skipWantedAt;

    /** Android 13+: accessibility screenshot + OK. Older (Mi Stick): screen capture + the Pi. */
    private boolean skipNative() {
        return android.os.Build.VERSION.SDK_INT >= 33 && PauseAdService.instance != null;
    }

    private void skipTick(long now) {
        if (!ytAd || skipChecking || skipPresses >= 3 || now < nextSkipCheck) return;
        final boolean nativeOk = skipNative();
        if (!nativeOk && !(ScreenGrabber.needed() && ScreenGrabber.ready())) return;
        skipChecking = true;
        PauseAdService.SkipResult onMeasure = new PauseAdService.SkipResult() {
            public void done(int[] m, float scale) {
                skipChecking = false;
                if (!ytAd) return;
                boolean can = SkipDetector.skippable(m, scale);
                Log.i(TAG, "skip check pill=" + m[0] + " countdown=" + m[1] + (can ? " -> press OK" : ""));
                if (can && !nativeOk) {
                    skipPresses++;
                    skipWantedAt = System.currentTimeMillis();
                    nextSkipCheck = skipWantedAt + 2500;
                    Log.i(TAG, "skip wanted (for the Pi to press OK)");
                } else if (can && PauseAdService.instance != null && PauseAdService.instance.pressOk()) {
                    skipPresses++;
                    nextSkipCheck = System.currentTimeMillis() + 2000;   // give YouTube time to move on
                } else {
                    // the result lands ~0.1 s after a once-a-second tick: 800 ms keeps the next
                    // tick eligible, so the check really runs every second
                    nextSkipCheck = System.currentTimeMillis() + 800;
                }
            }
        };
        if (nativeOk) PauseAdService.instance.checkSkip(onMeasure);
        else ScreenGrabber.checkSkip(this, onMeasure);
    }

    private void resetCountdown() {
        breakEndsAt = 0;
        countdownTries = 0;
        holding = false;
        nextCountdownRead = System.currentTimeMillis() + 1000;   // the badge appears with the ad
    }

    private boolean screenReadable() {
        if (ScreenGrabber.needed()) return ScreenGrabber.ready();
        return PauseAdService.instance != null;
    }

    private void readCountdown(final PauseAdService.CountResult r) {
        cdReading = true;
        PauseAdService.CountResult done = new PauseAdService.CountResult() {
            public void done(int secs) { cdReading = false; lastCountdownRead = System.currentTimeMillis(); r.done(secs); }
        };
        if (ScreenGrabber.needed()) ScreenGrabber.checkCountdown(this, done);
        else PauseAdService.instance.checkCountdown(done);
    }

    /** Once a second while a Prime ad is on (or held): read / re-check the countdown. */
    private void countdownTick(long now) {
        if (!videoAd || ytAd || cdReading || !screenReadable()) return;
        if (holding) {
            if (now - lastCountdownRead < HOLD_CHECK_MS) return;
            readCountdown(new PauseAdService.CountResult() { public void done(int secs) {
                if (!holding) return;
                long t = System.currentTimeMillis();
                if (secs < 0 || t > breakEndsAt + 3000) {
                    Log.i(TAG, "countdown gone (" + secs + "): break over");
                    holding = false;
                    endVideoAd("break over");
                } else {
                    breakEndsAt = t + secs * 1000L;
                    if (view != null) view.setCountdown(secs);
                    Log.i(TAG, "still holding, countdown " + fmt(secs));
                }
            } });
            return;
        }
        if (breakEndsAt == 0 && countdownTries < 3 && now >= nextCountdownRead) {
            countdownTries++;
            readCountdown(new PauseAdService.CountResult() { public void done(int secs) {
                if (!videoAd) return;
                if (secs > 0) {
                    breakEndsAt = System.currentTimeMillis() + secs * 1000L;
                    if (view != null) view.setCountdown(secs);
                    Log.i(TAG, "countdown " + fmt(secs) + " (break ends in " + secs + " s)");
                } else {
                    nextCountdownRead = System.currentTimeMillis() + 2000;
                }
            } });
        }
    }

    private static String fmt(int secs) {
        return secs < 0 ? "-" : (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60);
    }

    private void startVideoAd() {
        if (videoAd) {
            if (holding) { holding = false; Log.i(TAG, "next ad of the break, still muted"); }
            return;
        }
        videoAd = true;
        userOverride = false;
        resetCountdown();
        videoAdSince = System.currentTimeMillis();
        if (weMuted) {                     // our mute from before a restart: keep holding it
            if (!isMuted()) setMuted(true);
            Log.i(TAG, "video ad, still holding our mute from before a restart");
        } else if (!isMuted()) {
            setWeMuted(true);  // before muting, so a crash right after still knows
            setMuted(true);
            Log.i(TAG, "MUTED (video ad)");
        } else {
            Log.i(TAG, "video ad, but already muted by you: leaving the sound alone");
        }
        show(Math.round(darkness() * 255 / 100f), -1);
    }

    private void endVideoAd(String why) {
        if (!videoAd) return;
        if (why.equals("ad over") && !ytAd && breakEndsAt - System.currentTimeMillis() > HOLD_MIN_LEFT_MS) {
            if (!holding) {
                holding = true;
                lastCountdownRead = 0;   // re-check right away
                Log.i(TAG, "Prime says the ad is over, but the countdown has "
                        + (breakEndsAt - System.currentTimeMillis()) / 1000 + " s left: holding");
            }
            return;
        }
        holding = false;
        breakEndsAt = 0;
        videoAd = false;
        if (weMuted) {
            if (isMuted()) setMuted(false);
            setWeMuted(false);
            Log.i(TAG, "UNMUTED (" + why + ")");
        }
        userOverride = false;
        if (!pauseAd) hide();
    }

    private void endPauseAd() {
        if (!pauseAd) return;
        pauseAd = false;
        Log.i(TAG, "pause ad gone");
        if (!videoAd) hide();
    }

    /** Once a second: mute safety and pause-ad checks. */
    private void autoTick() {
        long now = System.currentTimeMillis();
        countdownTick(now);
        if (weMuted && !videoAd && !SessionListener.haveReading && !watch && now - startedAt > RESTORE_GIVE_UP_MS) {
            if (isMuted()) setMuted(false);
            setWeMuted(false);
            hide();
            Log.i(TAG, "UNMUTED (no reading " + RESTORE_GIVE_UP_MS / 1000 + " s after a restart)");
        }
        if (watch) watchTick(now);
        skipTick(now);
        if (videoAd && weMuted) {
            if (!isMuted()) {          // you unmuted during the ad: respect it
                setWeMuted(false);
                userOverride = true;
                Log.i(TAG, "you unmuted during the ad; leaving it");
            } else if (now - videoAdSince > MUTE_CAP_MS) {
                setMuted(false);
                setWeMuted(false);
                Log.i(TAG, "UNMUTED (mute time limit)");
            }
        }
        if (videoAd) show(Math.round(darkness() * 255 / 100f), -1);   // keep the cover up
        if (auto && paused && !checking && now >= nextPauseCheck && now - pausedSince < PAUSE_AD_GIVE_UP_MS
                && screenshotsReady()) {
            checking = true;
            checkScreen(new PauseAdService.Result() {
                public void done(boolean ad) {
                    checking = false;
                    nextPauseCheck = System.currentTimeMillis() + PAUSE_AD_CHECK_EVERY_MS;
                    if (!paused) return;
                    if (ad && !pauseAd) {
                        pauseAd = true;
                        Log.i(TAG, "pause ad on screen: cover ON");
                        show(Math.round(darkness() * 255 / 100f), -1);
                    } else if (!ad && pauseAd) {
                        endPauseAd();      // closed with Back
                    } else if (ad) {
                        show(Math.round(darkness() * 255 / 100f), -1);
                    }
                }
            });
        }
    }

    private void watchTick(long now) {
        if (ytAd || ytEnding) return;    // YouTube's ad is handled by its own session
        boolean prime = primeInFront();
        if (!auto || !prime) {
            if (videoAd) endVideoAd(prime ? "auto off" : "left Prime");
            if (pauseAd) endPauseAd();
            paused = false;
            quietSince = 0;
            if (!videoAd && weMuted) resolveLeftoverMute();
            return;
        }
        boolean playing = false;
        try { playing = audio().isMusicActive(); } catch (Exception ignored) { }
        if (playing) {
            quietSince = 0;
            if (paused) { paused = false; endPauseAd(); }
        } else if (!videoAd) {
            if (quietSince == 0) quietSince = now;
            if (!paused && now - quietSince >= 1000) {   // quiet for a second: treat as paused
                paused = true;
                pausedSince = quietSince;
                nextPauseCheck = pausedSince + PAUSE_AD_FIRST_CHECK_MS;
            }
        }
        if ((playing || videoAd) && !watchChecking && !checking && screenshotsReady()) badgeCheck();
    }

    private void badgeCheck() {
        watchChecking = true;
        checkScreen(new PauseAdService.Result() {
            public void done(boolean ad) {
                watchChecking = false;
                if (!watch || !auto || !primeInFront()) return;
                if (ad) {
                    watchMisses = 0;
                    if (!videoAd) { Log.i(TAG, "badge seen"); startVideoAd(); }
                } else if (videoAd) {
                    if (++watchMisses >= 2) { watchMisses = 0; endVideoAd("badge gone"); }
                    else main.postDelayed(new Runnable() { public void run() {
                        if (videoAd && !watchChecking && screenshotsReady()) badgeCheck();
                    } }, WATCH_CONFIRM_MS);
                } else if (weMuted) {
                    resolveLeftoverMute();
                }
            }
        });
    }

    /** Prime is in front; if no app switch has been seen yet (just installed), its live session counts. */
    private static boolean primeInFront() {
        String fg = PauseAdService.foreground;
        return fg.isEmpty() ? SessionListener.primeSession : PRIME.equals(fg);
    }

    private void resolveLeftoverMute() {
        if (isMuted()) setMuted(false);
        setWeMuted(false);
        Log.i(TAG, "UNMUTED (our mute from before a restart; no ad now)");
    }

    /** Android 11+: accessibility screenshot. Android 8-10: screen capture (ScreenGrabber). */
    private boolean screenshotsReady() {
        if (ScreenGrabber.needed()) {
            if (!ScreenGrabber.ready()) ScreenGrabber.request(this);
            return ScreenGrabber.ready();
        }
        return PauseAdService.instance != null;
    }

    private void checkScreen(PauseAdService.Result r) {
        if (ScreenGrabber.needed()) ScreenGrabber.check(this, r);
        else PauseAdService.instance.check(r);
    }

    private int darkness() { return prefs.getInt("darkness", 98); }

    private void setWeMuted(boolean on) {
        weMuted = on;
        prefs.edit().putBoolean("weMuted", on).commit();
    }

    private boolean isMuted() {
        try { return audio().isStreamMute(AudioManager.STREAM_MUSIC); } catch (Exception e) { return false; }
    }

    private void setMuted(boolean on) {
        try {
            audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception e) {
            Log.e(TAG, "could not " + (on ? "mute" : "unmute"), e);
        }
    }

    private AudioManager audio() {
        return (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private void show(int alpha, int seconds) {
        if (view == null) {
            view = new CoverView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // No FLAG_NOT_TOUCHABLE: Android 12+ caps touch-through overlays from other apps at
                    // 80% opacity (seen as alpha=0.8 on the Mi TV Box 4S). TVs have no touchscreen,
                    // and NOT_FOCUSABLE keeps every remote key going to Prime.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            try {
                wm.addView(view, lp);
            } catch (Exception e) {
                Log.e(TAG, "could not add overlay (is SYSTEM_ALERT_WINDOW allowed?)", e);
                view = null;
                return;
            }
        }
        view.setAlpha255(alpha);
        if (seconds >= 0) view.setCountdown(seconds);
        shown = true;
    }

    private void hide() {
        if (view != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
            view = null;
        }
        shown = false;
    }

    @Override
    public void onDestroy() {
        if (weMuted) { setMuted(false); setWeMuted(false); }   // never leave the TV muted
        instance = null;
        DumpPoller.stop();
        hide();
        super.onDestroy();
    }

    /**
     * "Frosted glass": a cool grey gradient at high opacity plus fine film grain, so only soft
     * light and colour changes of the ad show through. A countdown sits in the middle.
     * The badge corner stays clear so the watcher (and you) can still see "Ad 0:19".
     */
    static class CoverView extends View {
        private final Paint glass = new Paint();
        private final Paint grain = new Paint();
        private final Paint rim = new Paint();
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint credit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 235;
        private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint quote = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
        private final java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.US);
        // Shown one at a time while an ad plays, changing every QUOTE_MS. Original lines.
        private static final String[] QUOTES = {
            "This ad is talking to an empty room. Rude, but deserved.",
            "Fun fact: you've just saved your ears from a jingle.",
            "Somewhere, a marketing team is crying into their KPIs.",
            "Perfect time to stretch. Your back has been asking.",
            "Go grab a snack. The ad won't notice you're gone.",
            "Shhh... the ad thinks you're watching.",
            "Plot twist: the ad was never that interesting.",
            "Hydration check! Water is cheaper than whatever they're selling.",
            "You can't buy what you can't hear.",
            "Ad detected. Volume deployed to an undisclosed location.",
            "This screen intentionally left boring. Like the ad.",
            "Patience is a virtue. Muting is a superpower.",
            "Breathe in... breathe out... the movie is coming back.",
            "Loading your movie back... unlike the ad, this is worth it.",
            "Blink twice if you miss the jingle. (Nobody blinked.)",
            "The ad is still going. We are still not listening.",
            "Fun fact: the average ad wants 30 seconds of your life. Denied.",
            "Your wallet thanks you for not hearing that.",
            "Quick! Name three snacks within reach. Go!",
            "Interval time. Popcorn status: critical?",
            "We'll be right back. Probably sooner than the ad wants.",
            "No ears were harmed during this commercial break.",
            "This is your sign to check on the chai.",
            "Somewhere a jingle is playing. Not here though.",
            "Rest your eyes. The good part is coming.",
            "विज्ञापन चल रहा है... और हम सुन ही नहीं रहे। मज़ा आ गया!",
            "थोड़ा रुकिए, पिक्चर अभी बाकी है मेरे दोस्त।",
            "चाय बन गई क्या? यही सही मौका है।",
            "विज्ञापन वालों का दिल टूट गया, आवाज़ जो बंद है।",
            "जो सुनाई ही न दे, वो खरीदा भी न जाए।",
            "बस दो मिनट... मैगी से भी जल्दी!",
            "इंटरवल हो गया! पॉपकॉर्न की हालत कैसी है?",
            "आराम से बैठिए, हीरो की एंट्री अभी बाकी है।",
            "ये विज्ञापन खाली कमरे से बात कर रहा है।",
            "पानी पी लीजिए, सेहत भी ज़रूरी है।",
            "विज्ञापन आया, आवाज़ गई। हिसाब बराबर।",
            "धीरज रखिए, अच्छी चीज़ें इंतज़ार के बाद ही आती हैं।",
            "शांति बनाए रखें, विज्ञापन चालू है।",
            "पीठ सीधी कर लीजिए, कब से झुके बैठे हैं।",
            "ये पल आपका है, विज्ञापन का नहीं।"
        };
        private static final long QUOTE_MS = 7000;
        private final long shownAt = System.currentTimeMillis();
        private static final int ENGLISH = 25;   // QUOTES[0..24] English, the rest Hindi
        private final int startEn = new java.util.Random().nextInt(ENGLISH);
        private final int startHi = new java.util.Random().nextInt(QUOTES.length - ENGLISH);
        private final int startLang = new java.util.Random().nextInt(2);
        private int seconds = -1;          // last countdown received
        private long secondsAt;            // when it was received
        private final Handler tick = new Handler(Looper.getMainLooper());
        private final Runnable redraw = new Runnable() {
            public void run() { invalidate(); tick.postDelayed(this, 250); }
        };

        CoverView(Context c) {
            super(c);
            // fine monochrome grain, tiled
            android.graphics.Bitmap noise = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888);
            java.util.Random rnd = new java.util.Random(7);
            for (int y = 0; y < 192; y++) {
                for (int x = 0; x < 192; x++) {
                    int v = 128 + (int) (rnd.nextGaussian() * 40);
                    v = Math.max(0, Math.min(255, v));
                    noise.setPixel(x, y, Color.argb(22, v, v, v));
                }
            }
            grain.setShader(new android.graphics.BitmapShader(noise,
                    android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(2f);
            rim.setColor(Color.argb(60, 255, 255, 255));
            small.setColor(Color.argb(200, 225, 232, 240));
            small.setTextAlign(Paint.Align.CENTER);
            small.setLetterSpacing(0.12f);
            big.setColor(Color.argb(235, 245, 248, 252));
            big.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            credit.setColor(Color.argb(110, 225, 232, 240));
            credit.setTextAlign(Paint.Align.CENTER);
            credit.setLetterSpacing(0.06f);
            title.setColor(Color.argb(150, 214, 222, 232));
            title.setTextAlign(Paint.Align.LEFT);
            title.setLetterSpacing(0.18f);
            title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            quote.setColor(Color.argb(215, 214, 222, 232));
            quote.setTextAlign(Paint.Align.CENTER);
            quote.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.ITALIC));
            clock.setColor(Color.argb(230, 240, 244, 250));
            clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            date.setColor(Color.argb(170, 214, 222, 232));
            date.setLetterSpacing(0.04f);
            tick.post(redraw);
        }

        void setAlpha255(int a) {
            alpha = a;
            invalidate();
        }

        void setCountdown(int s) {
            seconds = s;
            secondsAt = System.currentTimeMillis();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            tick.removeCallbacks(redraw);
            super.onDetachedFromWindow();
        }

        private int remaining() {
            if (seconds < 0) return -1;
            long gone = (System.currentTimeMillis() - secondsAt) / 1000;
            return (int) Math.max(0, seconds - gone);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float l = HOLE_L * w, t = HOLE_T * h, r = HOLE_R * w, b = HOLE_B * h;
            if (youtubeHole) {   // YouTube: keep the Skip pill visible (bottom-right) instead
                l = 1600f / 1920 * w; t = 915f / 1080 * h; r = 1875f / 1920 * w; b = 1030f / 1080 * h;
            }

            glass.setShader(new android.graphics.LinearGradient(0, 0, w * 0.35f, h,
                    Color.argb(alpha, 58, 66, 78), Color.argb(alpha, 24, 28, 34),
                    android.graphics.Shader.TileMode.CLAMP));

            c.save();
            c.clipRect(l, t, r, b, android.graphics.Region.Op.DIFFERENCE);  // keep badge corner clear
            c.drawRect(0, 0, w, h, glass);
            c.drawRect(0, 0, w, h, grain);
            c.restore();
            c.drawRoundRect(l, t, r, b, 18, 18, rim);

            float cx = w / 2, cy = h / 2;
            int rem = remaining();
            if (plainStyle) {   // the original look: one line, centred
                title.setTextAlign(Paint.Align.CENTER);
                title.setTextSize(h * 0.06f);
                c.drawText("AD PLAYING", cx, cy + h * 0.02f, title);
                title.setTextAlign(Paint.Align.LEFT);
                return;
            }
            // one clock only: the break's countdown goes into the small label under the clock
            drawQuote(c, w, h, cx, cy + h * 0.02f);

            // date and time, top-left (the ad badge is top-right)
            java.util.Date now = new java.util.Date();
            clock.setTextSize(h * 0.085f);
            date.setTextSize(h * 0.034f);
            float x = w * 0.045f;
            c.drawText(timeFmt.format(now), x, h * 0.130f, clock);
            c.drawText(dateFmt.format(now), x, h * 0.190f, date);
            title.setTextSize(h * 0.028f);
            c.drawText(rem >= 0 ? String.format(java.util.Locale.US, "AD PLAYING  ·  BACK IN %d:%02d", rem / 60, rem % 60)
                                : "AD PLAYING", x, h * 0.250f, title);
        }

        /** Current quote, word-wrapped to 70% of the width, fading in for its first half second. */
        private void drawQuote(Canvas c, float w, float h, float cx, float top) {
            long age = System.currentTimeMillis() - shownAt;
            long k = age / QUOTE_MS;                 // alternate English and Hindi
            boolean hindi = ((k + startLang) % 2) == 1;
            long n = (k + startLang) / 2;
            String text = hindi ? QUOTES[ENGLISH + (int) ((startHi + n) % (QUOTES.length - ENGLISH))]
                                : QUOTES[(int) ((startEn + n) % ENGLISH)];
            String q = "\u201C" + text + "\u201D";
            float fade = Math.min(1f, (age % QUOTE_MS) / 500f);
            quote.setTextSize(h * 0.058f);
            quote.setAlpha(Math.round(215 * fade));
            float maxW = w * 0.80f, lineH = h * 0.080f;
            java.util.List<String> lines = new java.util.ArrayList<String>();
            StringBuilder line = new StringBuilder();
            for (String word : q.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (quote.measureText(tryLine) > maxW && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            for (int i = 0; i < lines.size(); i++) c.drawText(lines.get(i), cx, top + i * lineH, quote);
        }
    }
}
