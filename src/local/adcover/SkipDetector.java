package local.adcover;

import android.graphics.Bitmap;

/**
 * Finds YouTube TV's "Skip" pill in the bottom-right corner and tells whether it can be pressed.
 *
 * Measured on the Mi TV Box 4S at 1920x1080: the pill is light grey (241,241,241), roughly
 * (1630,936)-(1842,1007), about 9,400 such pixels. YouTube only shows it once the ad can be skipped
 * (5-6 s in). The dark grey disc (96,96,96) at its right end shows the seconds left IN THE AD, not
 * a skip countdown (a "Skip 5" ad ended 5 s later), so it is measured for the log but not used.
 */
final class SkipDetector {
    private SkipDetector() { }

    /** {pillPixels, discPixels} in the bottom-right search box, scaled to the screen size. */
    static int[] measure(Bitmap shot) {
        int w = shot.getWidth(), h = shot.getHeight();
        float s = w / 1920f;
        int left = Math.round(1600 * s), top = Math.round(915 * s);
        int right = Math.min(w, Math.round(1875 * s)), bottom = Math.min(h, Math.round(1030 * s));
        int rw = right - left, rh = bottom - top;
        if (rw <= 0 || rh <= 0) return new int[] {0, 0};
        int[] px = new int[rw * rh];
        shot.getPixels(px, 0, rw, left, top, rw, rh);
        int pill = 0, disc = 0;
        for (int c : px) {
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            if (Math.abs(r - g) > 6 || Math.abs(g - b) > 6) continue;   // greys only
            if (r >= 228 && r <= 250) pill++;
            else if (r >= 88 && r <= 104) disc++;
        }
        return new int[] {pill, disc};
    }

    static boolean skippable(int[] m, float scale) {
        float a = scale * scale;
        return m[0] > 3000 * a;   // the pill only appears once skipping is allowed
    }
}
