package local.adcover;

import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.List;

/**
 * Watches Prime Video's media session. Being a notification listener is what lets an app read
 * other apps' media sessions; it never looks at notifications.
 *
 * Measured on the BenQ QS01 (Android TV 12), Prime reports:
 *   show playing   state=PLAYING  seeking allowed
 *   video ad       state=PLAYING  seeking NOT allowed (and speed 0, position frozen)
 *   paused         state=PAUSED   (a still pause-screen ad may appear ~5-7 s later)
 *   buffering      state=BUFFERING
 * "Seeking allowed" flips first at both ends of an ad; speed lags up to ~7 s, so it's ignored.
 */
public class SessionListener extends NotificationListenerService {
    static final String TAG = "AdCover";
    static final String PRIME = "com.amazon.amazonvideo.livingroom";
    static final String YOUTUBE = "com.google.android.youtube.tv";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController prime;
    private MediaController youtube;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> list) { attach(list); }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { report(state); }
        @Override public void onSessionDestroyed() { detach(); report(null); }
    };

    /*
     * YouTube TV (measured on the Mi TV Box 4S, Android 14) reports ads the same way Prime does on
     * the QS01: playing, with seek / fast-forward / rewind taken away (actions 51 during the ad,
     * 379 in the video).
     */
    private final MediaController.Callback ytCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { reportYouTube(state); }
        @Override public void onSessionDestroyed() { detachYouTube(); reportYouTube(null); }
    };

    @Override
    public void onListenerConnected() {
        startForegroundService(new Intent(this, CoverService.class));
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, SessionListener.class);
        msm.addOnActiveSessionsChangedListener(sessions, me, main);
        attach(msm.getActiveSessions(me));
        connected = true;
        Log.i(TAG, "session listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        detach();
        detachYouTube();
        reportYouTube(null);
        report(null);
        requestRebind(new ComponentName(this, SessionListener.class));
    }

    private void attach(List<MediaController> list) {
        attachYouTube(list);
        MediaController found = null;
        if (list != null) {
            for (MediaController c : list) {
                if (PRIME.equals(c.getPackageName())) { found = c; break; }
            }
        }
        if (found != null && prime != null && found.getSessionToken().equals(prime.getSessionToken())) return;
        detach();
        prime = found;
        primeSession = prime != null;
        if (prime != null) {
            prime.registerCallback(callback, main);
            Log.i(TAG, "watching Prime Video's session");
        }
        report(prime == null ? null : prime.getPlaybackState());
    }

    private void attachYouTube(List<MediaController> list) {
        MediaController found = null;
        if (list != null) {
            for (MediaController c : list) {
                if (YOUTUBE.equals(c.getPackageName())) { found = c; break; }
            }
        }
        if (found != null && youtube != null && found.getSessionToken().equals(youtube.getSessionToken())) return;
        detachYouTube();
        youtube = found;
        if (youtube != null) {
            youtube.registerCallback(ytCallback, main);
            Log.i(TAG, "watching YouTube's session");
        }
        reportYouTube(youtube == null ? null : youtube.getPlaybackState());
    }

    private void detachYouTube() {
        if (youtube != null) {
            try { youtube.unregisterCallback(ytCallback); } catch (Exception ignored) { }
            youtube = null;
        }
    }

    private void reportYouTube(PlaybackState s) {
        applyYouTube(s == null ? PlaybackState.STATE_NONE : s.getState(), s == null ? 0 : s.getActions());
    }

    /** Shared by the listener and DumpPoller: a YouTube ad is "playing, but you can't seek". */
    static void applyYouTube(int st, long actions) {
        haveReading = true;
        // actions == 0 is YouTube's first moment of every new video (nothing set up yet), not an ad
        boolean ad = st == PlaybackState.STATE_PLAYING && actions != 0 && (actions & PlaybackState.ACTION_SEEK_TO) == 0;
        lastYouTubeAd = ad;
        CoverService svc = CoverService.instance;
        if (svc != null) svc.onYouTubeState(ad);
    }

    private void detach() {
        if (prime != null) {
            try { prime.unregisterCallback(callback); } catch (Exception ignored) { }
            prime = null;
        }
    }

    private void report(PlaybackState s) {
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        apply(this, st, s == null ? 0 : s.getActions());
    }

    /** Shared by the listener and DumpPoller: turn Prime's state + actions into ad / paused. */
    static void apply(android.content.Context c, int st, long actions) {
        haveReading = true;
        boolean seekable = (actions & PlaybackState.ACTION_SEEK_TO) != 0;
        boolean videoAd = st == PlaybackState.STATE_PLAYING && !seekable;
        boolean paused = st == PlaybackState.STATE_PAUSED;
        lastVideoAd = videoAd;
        lastPaused = paused;
        CoverService svc = CoverService.instance;
        if (svc != null) {
            svc.onPrimeState(videoAd, paused);
        } else {
            c.startForegroundService(new Intent(c, CoverService.class));
        }  // else: the service reads lastVideoAd/lastPaused when it starts
    }

    static volatile boolean lastVideoAd, lastPaused, connected;
    /** Prime has an active media session (used when we don't know which app is in front). */
    static volatile boolean primeSession;
    static volatile boolean lastYouTubeAd;
    /** A real reading of Prime's or YouTube's state has arrived since this process started. */
    static volatile boolean haveReading;
}
