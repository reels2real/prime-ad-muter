package local.adcover;

/**
 * Reads Prime Video's ad countdown ("Ad 1:26") from a screenshot, so the app knows how long the
 * whole ad break still has to run.
 *
 * Plain Java on ARGB pixels (no Android types), so it can be tested off the device.
 * Finds the yellow "Ad" word in the top-right corner, takes the white glyphs to its right, splits
 * them at empty columns, spots the colon by the gap between its two dots, shrinks each digit to an
 * 8x12 grid of "how much of this cell is white" and picks the closest of ten templates.
 *
 * Templates: averaged from real Mi Stick screenshots (1920x1080) of a 1:26 -> 0:01 break, all 40
 * strips read correctly; the right digit is always within 7.2 points (of 96) and the runner-up is
 * always 15+ points further away. A reading is accepted only within 14 points and with a 6-point lead.
 */
final class CountdownReader {
    private CountdownReader() { }

    static final int GW = 8, GH = 12;
    private static final int[][] T = {
            {0,0,255,255,255,255,99,0,0,191,255,128,128,128,255,50,0,255,198,0,0,0,156,128,156,255,0,0,0,0,128,241,255,227,0,0,0,0,128,255,255,227,0,0,0,0,28,255,255,227,0,0,0,0,28,255,255,227,0,0,0,0,78,255,255,255,0,0,0,0,128,255,28,255,99,0,0,0,142,177,0,255,255,0,0,0,255,128,0,64,255,255,255,255,177,0},  // 0
            {0,0,0,0,0,255,255,255,128,128,149,255,255,255,255,255,255,255,255,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255},  // 1
            {0,42,255,255,255,255,170,0,255,234,128,128,128,212,255,85,0,0,0,0,0,42,255,212,0,0,0,0,0,42,255,212,0,0,0,0,0,42,255,85,0,0,0,0,0,212,212,42,0,0,0,0,85,255,0,0,0,0,0,149,255,149,0,0,0,0,170,255,170,0,0,0,0,149,255,106,0,0,0,0,255,255,85,0,0,0,0,0,255,255,255,255,255,255,255,255},  // 2
            {85,255,255,255,255,255,255,128,42,128,128,128,128,191,255,128,0,0,0,0,0,255,255,0,0,0,0,85,255,191,0,0,0,0,0,255,255,0,0,0,0,0,234,255,170,128,42,0,0,0,255,255,255,255,255,128,0,0,0,0,0,42,255,212,0,0,0,0,0,0,170,255,0,0,0,0,0,0,212,255,0,0,0,0,0,128,255,128,170,255,255,255,255,255,128,21},  // 3
            {0,0,0,0,0,255,255,0,0,0,0,0,191,255,255,0,0,0,0,0,255,255,255,0,0,0,0,191,191,255,255,0,0,0,0,255,0,255,255,0,0,64,255,128,0,255,255,0,0,255,255,0,0,255,255,0,128,191,0,0,0,255,255,0,255,255,255,255,255,255,255,255,128,128,128,128,128,255,255,128,0,0,0,0,0,255,255,0,0,0,0,0,0,255,255,0},  // 4
            {0,255,255,255,255,255,255,128,0,255,191,128,128,128,128,64,0,255,128,0,0,0,0,0,0,255,128,0,0,0,0,0,128,255,128,0,0,0,0,0,128,255,255,255,255,223,128,32,0,0,0,0,0,191,255,128,0,0,0,0,0,0,191,255,0,0,0,0,0,0,0,255,0,0,0,0,0,0,128,255,0,0,0,0,0,128,255,191,255,255,255,255,255,255,128,64},  // 5
            {0,0,0,255,255,255,255,0,0,64,255,191,128,128,128,64,0,128,255,0,0,0,0,0,0,255,128,0,0,0,0,0,0,255,0,0,0,0,0,0,255,255,128,191,255,255,128,0,255,255,255,128,0,255,255,128,255,255,0,0,0,0,128,255,0,255,0,0,0,0,128,255,0,255,128,0,0,0,128,255,0,128,255,128,0,0,255,128,0,64,255,255,255,255,191,0},  // 6
            {255,255,255,255,255,255,255,255,128,128,128,128,128,128,128,255,0,0,0,0,0,0,255,255,0,0,0,0,0,64,255,128,0,0,0,0,0,128,255,128,0,0,0,0,0,191,255,0,0,0,0,0,0,255,0,0,0,0,0,0,255,191,0,0,0,0,0,128,255,128,0,0,0,0,0,191,255,0,0,0,0,0,0,255,255,0,0,0,0,0,255,255,0,0,0,0},  // 7
            {0,0,255,255,255,255,128,0,0,191,255,128,128,128,255,64,0,255,0,0,0,0,128,128,0,255,128,0,0,0,128,128,0,255,255,0,0,0,255,128,0,64,255,255,128,255,191,0,0,128,255,128,255,255,128,0,0,255,128,0,0,128,191,128,255,255,0,0,0,0,128,255,255,255,0,0,0,0,128,255,0,255,255,0,0,0,255,128,0,128,255,255,255,255,191,64},  // 8
            {0,0,255,255,255,255,128,0,0,191,255,128,128,128,255,0,0,255,0,0,0,0,255,128,255,255,0,0,0,0,128,128,255,255,0,0,0,0,128,255,0,255,128,0,0,0,128,255,0,128,255,255,255,255,255,255,0,0,128,128,128,128,128,191,0,0,0,0,0,0,128,128,0,0,0,0,0,0,255,64,0,0,0,0,0,255,255,0,0,191,255,255,255,255,64,0},  // 9
    };

    /** Reads from the top strip of a screenshot only (keeps memory small on 1 GB devices). */
    static int read(android.graphics.Bitmap bmp) {
        int w = bmp.getWidth(), h = Math.min(bmp.getHeight(), Math.round(160 * w / 1920f));
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return read(px, w, h);
    }

    /** Seconds left in the ad break, or -1 if no countdown could be read with confidence. */
    static int read(int[] px, int w, int h) {
        float s = w / 1920f;
        int sx0 = Math.round(1560 * s), sx1 = Math.min(w - 1, Math.round(1900 * s));
        int sy0 = Math.round(60 * s), sy1 = Math.min(h - 1, Math.round(140 * s));
        int ax1 = -1, ay0 = Integer.MAX_VALUE, ay1 = -1, n = 0;
        for (int y = sy0; y < sy1; y++) {
            for (int x = sx0; x < sx1; x++) {
                if (yellow(px[y * w + x])) { n++; if (x > ax1) ax1 = x; if (y < ay0) ay0 = y; if (y > ay1) ay1 = y; }
            }
        }
        if (n < Math.round(50 * s * s)) return -1;
        int pad = Math.max(2, Math.round(6 * s));
        int top = Math.max(0, ay0 - pad), bot = Math.min(h - 1, ay1 + pad);
        int left = ax1 + pad, right = Math.min(w - 1, ax1 + Math.round(140 * s));
        int[] digits = new int[3];
        int nd = 0, colons = 0;
        int x = left;
        while (x <= right) {
            if (!colHasWhite(px, w, x, top, bot)) { x++; continue; }
            int a = x;
            while (x <= right && colHasWhite(px, w, x, top, bot)) x++;
            int b = x - 1;
            int gy0 = -1, gy1 = -1;
            for (int y = top; y <= bot; y++) if (rowHasWhite(px, w, y, a, b)) { if (gy0 < 0) gy0 = y; gy1 = y; }
            if (gy0 < 0) continue;
            int gh = gy1 - gy0 + 1, gw = b - a + 1;
            int gap = 0, best = 0;
            for (int y = gy0; y <= gy1; y++) {
                if (rowHasWhite(px, w, y, a, b)) gap = 0; else { gap++; if (gap > best) best = gap; }
            }
            if (best >= gh * 0.2 || gh < (ay1 - ay0) * 0.6) { colons++; continue; }
            if (nd >= 3) return -1;
            int d = match(px, w, a, gy0, gw, gh);
            if (d < 0) return -1;
            digits[nd++] = d;
        }
        if (nd != 3 || colons != 1) return -1;
        return digits[0] * 60 + digits[1] * 10 + digits[2];
    }

    private static int match(int[] px, int w, int a, int gy0, int gw, int gh) {
        int[] v = new int[GW * GH];
        for (int j = 0; j < GH; j++) {
            for (int i = 0; i < GW; i++) {
                double xa = a + i * (double) gw / GW, xb = a + (i + 1) * (double) gw / GW;
                double ya = gy0 + j * (double) gh / GH, yb = gy0 + (j + 1) * (double) gh / GH;
                int x0 = (int) xa, x1 = Math.max(x0 + 1, (int) xb), y0 = (int) ya, y1 = Math.max(y0 + 1, (int) yb);
                int c = 0, n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) { n++; if (white(px[yy * w + xx])) c++; }
                v[j * GW + i] = n == 0 ? 0 : c * 255 / n;
            }
        }
        int bestD = Integer.MAX_VALUE, second = Integer.MAX_VALUE, best = -1;
        for (int d = 0; d < 10; d++) {
            int sum = 0;
            for (int k = 0; k < v.length; k++) sum += Math.abs(v[k] - T[d][k]);
            if (sum < bestD) { second = bestD; bestD = sum; best = d; } else if (sum < second) second = sum;
        }
        // distances here are x255 of the Python ones: accept within 14, with a 6-point lead
        if (bestD > 14 * 255 || second - bestD < 6 * 255) return -1;
        return best;
    }

    private static boolean colHasWhite(int[] px, int w, int x, int top, int bot) {
        for (int y = top; y <= bot; y++) if (white(px[y * w + x])) return true;
        return false;
    }

    private static boolean rowHasWhite(int[] px, int w, int y, int a, int b) {
        for (int x = a; x <= b; x++) if (white(px[y * w + x])) return true;
        return false;
    }

    private static boolean white(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
        return mn > 150 && mx - mn < 45;
    }

    private static boolean yellow(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        return r > 170 && g > 140 && b < 110 && r - b > 100;
    }
}
