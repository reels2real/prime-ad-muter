package local.adcover;

import android.graphics.Bitmap;

/**
 * Finds Prime Video's yellow "Ad" word in a screenshot (same rule as admute/detect.py).
 *
 * Measured at 1920x1080: the word is about 28x18 px, 100-400 yellow pixels, on a dark
 * background, inside the top-right corner box (1660,60)-(1840,140). Other screen sizes are
 * scaled. Yellow artwork (Prime's home-screen banners, a gold product photo next to the
 * pause-screen pill) is bigger, spread out or on a light background, so it doesn't count.
 */
final class BadgeDetector {
    private BadgeDetector() { }

    static int badgePixels(Bitmap shot) {
        int w = shot.getWidth(), h = shot.getHeight();
        float s = w / 1920f;
        int left = Math.round(1660 * s), top = Math.round(60 * s);
        int right = Math.min(w, Math.round(1840 * s)), bottom = Math.min(h, Math.round(140 * s));
        int rw = right - left, rh = bottom - top;
        if (rw <= 0 || rh <= 0) return 0;
        int[] px = new int[rw * rh];
        shot.getPixels(px, 0, rw, left, top, rw, rh);

        boolean[] yellow = new boolean[px.length];
        float[] luma = new float[px.length];
        boolean[] colHas = new boolean[rw];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            yellow[i] = r > 170 && g > 140 && b < 110 && r - b > 100;
            luma[i] = (r + g + b) / 3f;
            if (yellow[i]) colHas[i % rw] = true;
        }

        int gapMax = Math.max(2, Math.round(6 * s));
        int pad = Math.max(2, Math.round(5 * s));
        int wMin = Math.round(15 * s), wMax = Math.round(45 * s);
        int hMin = Math.round(12 * s), hMax = Math.round(26 * s);
        int pxMin = Math.round(100 * s * s), pxMax = Math.round(400 * s * s);

        int best = 0, x = 0;
        while (x < rw) {
            if (!colHas[x]) { x++; continue; }
            int x0 = x, last = x;
            for (int k = x + 1; k < rw && k - last <= gapMax; k++) {
                if (colHas[k]) last = k;
            }
            int x1 = last;
            x = x1 + 1;

            int y0 = -1, y1 = -1, count = 0;
            for (int y = 0; y < rh; y++) {
                for (int k = x0; k <= x1; k++) {
                    if (yellow[y * rw + k]) {
                        count++;
                        if (y0 < 0) y0 = y;
                        y1 = y;
                    }
                }
            }
            int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
            if (cw < wMin || cw > wMax || ch < hMin || ch > hMax || count < pxMin || count > pxMax) continue;

            double sum = 0;
            int n = 0;
            for (int y = Math.max(0, y0 - pad); y < Math.min(rh, y1 + pad + 1); y++) {
                for (int k = Math.max(0, x0 - pad); k < Math.min(rw, x1 + pad + 1); k++) {
                    int i = y * rw + k;
                    if (!yellow[i]) { sum += luma[i]; n++; }
                }
            }
            if (n > 0 && sum / n > 110) continue;
            best = Math.max(best, count);
        }
        return best;
    }

    static boolean isAd(Bitmap shot) {
        return badgePixels(shot) > 0;
    }
}
