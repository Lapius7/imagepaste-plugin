package dev.lapius7.imagepaste;

import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 画像を W x H タイルの地図ピクセル(減色済み)に変換する。非同期スレッドで呼ぶこと。 */
public final class ImageProcessor {
    public static final int TILE = 128;

    @com.github.bsideup.jabel.Desugar
    public record Result(int cols, int rows, byte[][] tiles) {}

    // --- パレット ---
    private static volatile int[] palR, palG, palB;
    private static volatile byte[] palIdx;
    private static final byte[] cache = new byte[1 << 18]; // 6bit/ch。0 = 未計算

    private static synchronized void initPalette() {
        if (palR != null) return;
        List<int[]> l = new ArrayList<>();
        List<Byte> idx = new ArrayList<>();
        for (int i = 4; i < 256; i++) {
            try {
                @SuppressWarnings("deprecation")
                Color c = MapPalette.getColor((byte) i);
                if (c == null || c.getAlpha() == 0) continue;
                l.add(new int[]{c.getRed(), c.getGreen(), c.getBlue()});
                idx.add((byte) i);
            } catch (RuntimeException e) { break; }
        }
        int n = l.size();
        if (n < 16) throw new IllegalStateException("地図パレットを読めません");
        int[] r = new int[n], g = new int[n], b = new int[n];
        byte[] ix = new byte[n];
        for (int i = 0; i < n; i++) { r[i] = l.get(i)[0]; g[i] = l.get(i)[1]; b[i] = l.get(i)[2]; ix[i] = idx.get(i); }
        palG = g; palB = b; palIdx = ix; palR = r;
    }

    private static int nearest(int r, int g, int b) {
        int key = ((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2);
        int hit = cache[key];
        if (hit != 0) return hit & 0xff;
        int qr = (r & 0xfc) | 2, qg = (g & 0xfc) | 2, qb = (b & 0xfc) | 2;
        int best = 0; long bd = Long.MAX_VALUE;
        for (int i = 0; i < palR.length; i++) {
            // 人の目に合わせた重み付きの距離
            int dr = qr - palR[i], dg = qg - palG[i], db = qb - palB[i];
            long rm = (qr + palR[i]) / 2;
            long d = (((512 + rm) * dr * dr) >> 8) + 4L * dg * dg + (((767 - rm) * db * db) >> 8);
            if (d < bd) { bd = d; best = i; }
        }
        int v = palIdx[best] & 0xff;
        cache[key] = (byte) v;
        return v;
    }

    private static int paletteIndexOf(int rgb) {
        return nearest((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

    private static int paletteRgb(int index) {
        for (int i = 0; i < palIdx.length; i++) if ((palIdx[i] & 0xff) == index) return (palR[i] << 16) | (palG[i] << 8) | palB[i];
        return 0;
    }

    /** 画像サイズと設定から {横タイル数, 縦タイル数} を決める (純粋関数) */
    public static int[] dims(int iw, int ih, Opts o, int maxW, int maxH) {
        int cols = Math.max(1, Math.min(o.width, maxW <= 0 ? Integer.MAX_VALUE : maxW));
        int rows;
        if (o.height > 0) rows = Math.min(o.height, maxH);
        else rows = (int) Math.max(1, Math.min(maxH, Math.ceil(cols * (double) ih / iw)));
        return new int[]{cols, rows};
    }

    // --- 本体 ---
    /** 中止されたとき投げる */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() { super("cancelled", null, false, false); }
    }

    public static Result process(BufferedImage src, Opts o, int maxW, int maxH, int maxTiles) throws IllegalArgumentException {
        return process(src, o, maxW, maxH, maxTiles, y -> {}, () -> false);
    }

    /** rowProgress には処理済みの行数 (全体は縦タイル数 x 128) を、8行ごとに渡す */
    public static Result process(BufferedImage src, Opts o, int maxW, int maxH, int maxTiles,
                                 java.util.function.IntConsumer rowProgress, java.util.function.BooleanSupplier cancelled) throws IllegalArgumentException {
        initPalette();
        int iw = src.getWidth(), ih = src.getHeight();
        int[] dm = dims(iw, ih, o, maxW, maxH);
        int cols = dm[0], rows = dm[1];
        if ((long) cols * rows > 262144L) throw new IllegalArgumentException("タイル数が多すぎます (最大 262144)");
        if (maxTiles > 0 && (long) cols * rows > maxTiles) throw new IllegalArgumentException("タイル数が上限(" + maxTiles + ")を超えます: " + cols + "x" + rows);

        int pw = cols * TILE, ph = rows * TILE;
        int[] argb = new int[iw * ih];
        src.getRGB(0, 0, iw, ih, argb, 0, iw);
        if ((o.flip & 1) != 0) for (int y = 0; y < ih; y++)
            for (int x = 0; x < iw / 2; x++) { int a = y * iw + x, b = y * iw + iw - 1 - x; int t = argb[a]; argb[a] = argb[b]; argb[b] = t; }
        if ((o.flip & 2) != 0) for (int y = 0; y < ih / 2; y++)
            for (int x = 0; x < iw; x++) { int a = y * iw + x, b = (ih - 1 - y) * iw + x; int t = argb[a]; argb[a] = argb[b]; argb[b] = t; }

        // 目的の矩形 (キャンバス座標)
        double dw, dh;
        switch (o.mode) {
            case "stretch" -> { dw = pw; dh = ph; }
            case "fill" -> { double s = Math.max(pw / (double) iw, ph / (double) ih); dw = iw * s; dh = ih * s; }
            default -> { double s = Math.min(pw / (double) iw, ph / (double) ih); dw = iw * s; dh = ih * s; }
        }
        double dx = (pw - dw) / 2.0, dy = (ph - dh) / 2.0;

        // 大幅な縮小は先に箱型平均で縮める
        int fx = (int) Math.floor(iw / dw), fy = (int) Math.floor(ih / dh);
        int bx = Math.max(1, fx), by = Math.max(1, fy);
        if (!o.resample.equals("nearest") && (bx > 1 || by > 1)) {
            int nw = Math.max(1, iw / bx), nh = Math.max(1, ih / by);
            argb = boxShrink(argb, iw, ih, nw, nh);
            iw = nw; ih = nh;
        }

        int bgRgb = o.background == null ? 0 : o.background;
        boolean transparentBg = o.background == null;
        final double sx = iw / dw, sy = ih / dh;
        final int fw = iw, fh = ih;
        final int[] src2 = argb;

        // 1行ずつ処理する (大きな画像でも float 配列は数行分だけ)。Floyd-Steinberg は次の行にだけ誤差を渡す
        float[][] R = new float[2][pw], G = new float[2][pw], B = new float[2][pw];
        boolean[][] C = new boolean[2][pw];
        byte[][] tiles = new byte[cols * rows][TILE * TILE];
        boolean floyd = o.dither.equals("floyd");
        fillRow(0, pw, src2, fw, fh, sx, sy, dx, dy, transparentBg, bgRgb, o, R[0], G[0], B[0], C[0]);
        for (int y = 0; y < ph; y++) {
            if ((y & 7) == 0) { if (cancelled.getAsBoolean()) throw new Cancelled(); rowProgress.accept(y); }
            int c = y & 1, n = c ^ 1;
            if (y + 1 < ph) fillRow(y + 1, pw, src2, fw, fh, sx, sy, dx, dy, transparentBg, bgRgb, o, R[n], G[n], B[n], C[n]);
            float[] r = R[c], g = G[c], b = B[c]; boolean[] cl = C[c];
            int ty = y / TILE, py = y % TILE;
            for (int x = 0; x < pw; x++) {
                byte idx;
                if (cl[x]) idx = 0;
                else if (floyd) {
                    int cr = (int) clamp(r[x]), cg = (int) clamp(g[x]), cb = (int) clamp(b[x]);
                    int k = nearest(cr, cg, cb);
                    idx = (byte) k;
                    int pc = paletteRgbFast(k);
                    float er = cr - ((pc >> 16) & 0xff), eg = cg - ((pc >> 8) & 0xff), eb = cb - (pc & 0xff);
                    if (x + 1 < pw && !cl[x + 1]) { r[x + 1] += er * 7 / 16f; g[x + 1] += eg * 7 / 16f; b[x + 1] += eb * 7 / 16f; }
                    if (y + 1 < ph) {
                        float[] nr = R[n], ng = G[n], nb = B[n]; boolean[] nc = C[n];
                        if (x > 0 && !nc[x - 1]) { nr[x - 1] += er * 3 / 16f; ng[x - 1] += eg * 3 / 16f; nb[x - 1] += eb * 3 / 16f; }
                        if (!nc[x]) { nr[x] += er * 5 / 16f; ng[x] += eg * 5 / 16f; nb[x] += eb * 5 / 16f; }
                        if (x + 1 < pw && !nc[x + 1]) { nr[x + 1] += er / 16f; ng[x + 1] += eg / 16f; nb[x + 1] += eb / 16f; }
                    }
                } else if (o.dither.equals("ordered")) {
                    float t = (BAYER[y & 7][x & 7] / 64f - 0.5f) * 40f;
                    idx = (byte) nearest((int) clamp(r[x] + t), (int) clamp(g[x] + t), (int) clamp(b[x] + t));
                } else idx = (byte) nearest((int) clamp(r[x]), (int) clamp(g[x]), (int) clamp(b[x]));
                tiles[ty * cols + x / TILE][py * TILE + x % TILE] = idx;
            }
        }
        return new Result(cols, rows, tiles);
    }

    private static void fillRow(int y, int pw, int[] argb, int iw, int ih, double sx, double sy, double dx, double dy,
                                boolean transparentBg, int bgRgb, Opts o, float[] r, float[] g, float[] b, boolean[] clear) {
        for (int x = 0; x < pw; x++) {
            double u = (x + 0.5 - dx) * sx, v = (y + 0.5 - dy) * sy;
            double[] px = null;
            if (u >= 0 && v >= 0 && u < iw && v < ih) px = sample(argb, iw, ih, u, v, o.resample);
            double a = px == null ? 0 : px[3];
            double pr = px == null ? 0 : px[0], pg = px == null ? 0 : px[1], pb = px == null ? 0 : px[2];
            if (a < 0.5 && transparentBg) { clear[x] = true; continue; }
            clear[x] = false;
            double br = (bgRgb >> 16) & 0xff, bgc = (bgRgb >> 8) & 0xff, bb = bgRgb & 0xff;
            if (transparentBg) { a = 1; br = bgc = bb = 0; }
            double rr = pr * a + br * (1 - a), gg = pg * a + bgc * (1 - a), bl = pb * a + bb * (1 - a);
            double[] adj = adjust(rr, gg, bl, o);
            r[x] = (float) adj[0]; g[x] = (float) adj[1]; b[x] = (float) adj[2];
        }
    }

    private static double[] adjust(double r, double g, double b, Opts o) {
        if (o.saturation != 1) {
            double l = 0.299 * r + 0.587 * g + 0.114 * b;
            r = l + (r - l) * o.saturation; g = l + (g - l) * o.saturation; b = l + (b - l) * o.saturation;
        }
        double add = o.brightness * 255;
        r = (r - 128) * o.contrast + 128 + add;
        g = (g - 128) * o.contrast + 128 + add;
        b = (b - 128) * o.contrast + 128 + add;
        return new double[]{clamp(r), clamp(g), clamp(b)};
    }

    private static double clamp(double v) { return v < 0 ? 0 : Math.min(v, 255); }

    private static final int[][] BAYER = {
            {0, 32, 8, 40, 2, 34, 10, 42}, {48, 16, 56, 24, 50, 18, 58, 26},
            {12, 44, 4, 36, 14, 46, 6, 38}, {60, 28, 52, 20, 62, 30, 54, 22},
            {3, 35, 11, 43, 1, 33, 9, 41}, {51, 19, 59, 27, 49, 17, 57, 25},
            {15, 47, 7, 39, 13, 45, 5, 37}, {63, 31, 55, 23, 61, 29, 53, 21}};

    private static void quantize(byte[] out, float[] r, float[] g, float[] b, boolean[] clear, int w, int h, String dither) {
        switch (dither) {
            case "floyd" -> {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x;
                        if (clear[i]) { out[i] = 0; continue; }
                        int cr = (int) clamp(r[i]), cg = (int) clamp(g[i]), cb = (int) clamp(b[i]);
                        int idx = nearest(cr, cg, cb);
                        out[i] = (byte) idx;
                        int pc = paletteRgbFast(idx);
                        float er = cr - ((pc >> 16) & 0xff), eg = cg - ((pc >> 8) & 0xff), eb = cb - (pc & 0xff);
                        spread(r, g, b, clear, w, h, x + 1, y, er, eg, eb, 7 / 16f);
                        spread(r, g, b, clear, w, h, x - 1, y + 1, er, eg, eb, 3 / 16f);
                        spread(r, g, b, clear, w, h, x, y + 1, er, eg, eb, 5 / 16f);
                        spread(r, g, b, clear, w, h, x + 1, y + 1, er, eg, eb, 1 / 16f);
                    }
                }
            }
            case "ordered" -> {
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        int i = y * w + x;
                        if (clear[i]) { out[i] = 0; continue; }
                        float t = (BAYER[y & 7][x & 7] / 64f - 0.5f) * 40f;
                        out[i] = (byte) nearest((int) clamp(r[i] + t), (int) clamp(g[i] + t), (int) clamp(b[i] + t));
                    }
            }
            default -> {
                for (int i = 0; i < out.length; i++)
                    out[i] = clear[i] ? 0 : (byte) nearest((int) clamp(r[i]), (int) clamp(g[i]), (int) clamp(b[i]));
            }
        }
    }

    private static int[] rgbByIndex;
    private static int paletteRgbFast(int idx) {
        if (rgbByIndex == null) {
            int[] t = new int[256];
            for (int i = 0; i < palIdx.length; i++) t[palIdx[i] & 0xff] = (palR[i] << 16) | (palG[i] << 8) | palB[i];
            rgbByIndex = t;
        }
        return rgbByIndex[idx];
    }

    private static void spread(float[] r, float[] g, float[] b, boolean[] clear, int w, int h, int x, int y, float er, float eg, float eb, float k) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int i = y * w + x;
        if (clear[i]) return;
        r[i] += er * k; g[i] += eg * k; b[i] += eb * k;
    }

    // --- リサンプリング ---
    private static int[] boxShrink(int[] src, int w, int h, int nw, int nh) {
        int[] dst = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int y0 = (int) ((long) y * h / nh), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * h / nh));
            for (int x = 0; x < nw; x++) {
                int x0 = (int) ((long) x * w / nw), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * w / nw));
                long sa = 0, sr = 0, sg = 0, sb = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++)
                    for (int xx = x0; xx < x1; xx++) {
                        int p = src[yy * w + xx];
                        int a = p >>> 24;
                        sa += a; sr += (long) ((p >> 16) & 0xff) * a; sg += (long) ((p >> 8) & 0xff) * a; sb += (long) (p & 0xff) * a; n++;
                    }
                int a = (int) (sa / n);
                int rr = sa == 0 ? 0 : (int) (sr / sa), gg = sa == 0 ? 0 : (int) (sg / sa), bb = sa == 0 ? 0 : (int) (sb / sa);
                dst[y * nw + x] = (a << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }
        return dst;
    }

    /** 戻り値: r,g,b (0-255, 非乗算), a (0-1) */
    private static double[] sample(int[] p, int w, int h, double u, double v, String mode) {
        if (mode.equals("nearest")) {
            int c = p[Math.min(h - 1, (int) v) * w + Math.min(w - 1, (int) u)];
            return new double[]{(c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff, (c >>> 24) / 255.0};
        }
        double fu = u - 0.5, fv = v - 0.5;
        int x0 = (int) Math.floor(fu), y0 = (int) Math.floor(fv);
        double tx = fu - x0, ty = fv - y0;
        int from = mode.equals("bicubic") ? -1 : 0, to = mode.equals("bicubic") ? 2 : 1;
        double sa = 0, sr = 0, sg = 0, sb = 0, sw = 0;
        for (int j = from; j <= to; j++) {
            double wy = mode.equals("bicubic") ? cubic(ty - j) : (j == 0 ? 1 - ty : ty);
            int yy = Math.max(0, Math.min(h - 1, y0 + j));
            for (int i = from; i <= to; i++) {
                double wx = mode.equals("bicubic") ? cubic(tx - i) : (i == 0 ? 1 - tx : tx);
                int xx = Math.max(0, Math.min(w - 1, x0 + i));
                int c = p[yy * w + xx];
                double a = (c >>> 24) / 255.0, wt = wx * wy;
                sa += a * wt; sr += ((c >> 16) & 0xff) * a * wt; sg += ((c >> 8) & 0xff) * a * wt; sb += (c & 0xff) * a * wt; sw += wt;
            }
        }
        sa = Math.max(0, Math.min(1, sa));
        if (sa < 1e-6) return new double[]{0, 0, 0, 0};
        // 加重和 / 透明度の加重和 で非乗算に戻す (bicubic の負の重みも許容)
        double aw = sa;
        return new double[]{clamp(sr / aw), clamp(sg / aw), clamp(sb / aw), sa};
    }

    /** Catmull-Rom */
    private static double cubic(double x) {
        x = Math.abs(x);
        if (x < 1) return 1.5 * x * x * x - 2.5 * x * x + 1;
        if (x < 2) return -0.5 * x * x * x + 2.5 * x * x - 4 * x + 2;
        return 0;
    }
}
