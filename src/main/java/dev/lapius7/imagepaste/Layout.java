package dev.lapius7.imagepaste;

/**
 * タイル (col,row) の配置と額縁の回転を決める純粋関数。Bukkit に依存しない。
 * ベクトルは {x, y, z}。+x=東, +z=南, +y=上。
 */
public final class Layout {
    private Layout() {}

    /** 基準ブロック(左下)からのオフセットと、額縁の回転 (0..3 = 時計回り 0/90/180/270 度) */
    public record Tile(int dx, int dy, int dz, int rotation) {}

    public static int[] cross(int[] a, int[] b) {
        return new int[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static int[] neg(int[] a) { return new int[]{-a[0], -a[1], -a[2]}; }

    private static boolean eq(int[] a, int[] b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }

    /** 見る人から見て時計回りに 90 度: 画像の上 t → 右 (d x t)。d は視線方向 */
    public static int[] clockwise(int[] d, int[] t) { return cross(d, t); }

    /**
     * 画像の「上」の向き。
     * 壁: 真上。床: プレイヤーの向き(奥が上)。天井: プレイヤーの向きの逆(見上げたとき視界の上は後ろ)。
     * rotateQuarter で、見る人から見て時計回りに回す。
     */
    public static int[] imageUp(int[] face, int[] look, int rotateQuarter) {
        int[] d = neg(face);
        int[] u = face[1] == 0 ? new int[]{0, 1, 0} : face[1] > 0 ? look.clone() : neg(look);
        for (int i = 0; i < Math.floorMod(rotateQuarter, 4); i++) u = clockwise(d, u);
        return u;
    }

    /** {上, 右} の軸ベクトル */
    public static int[][] axes(int[] face, int[] look, int rotateQuarter) {
        int[] up = imageUp(face, look, rotateQuarter);
        return new int[][]{up, clockwise(neg(face), up)};
    }

    /** 貼り付け範囲の外接ブロック箱 (両端を含む) */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    /** 基準ブロックからのオフセットで表した外接箱 */
    public static Box footprint(int[] face, int[] look, int rotateQuarter, int cols, int rows) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int c : new int[]{0, cols - 1}) for (int r : new int[]{0, rows - 1}) {
            Tile t = tile(face, look, rotateQuarter, cols, rows, c, r);
            int[] v = {t.dx(), t.dy(), t.dz()};
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], v[i]); hi[i] = Math.max(hi[i], v[i]); }
        }
        return new Box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /** 額縁の回転 0 のとき、地図の上が向く方向 (上向き面=北, 下向き面=南) */
    public static int[] mapTopAtRotation0(int[] face) {
        if (face[1] > 0) return new int[]{0, 0, -1};
        if (face[1] < 0) return new int[]{0, 0, 1};
        return new int[]{0, 1, 0}; // 壁
    }

    /** 地図の上を u に向けるのに必要な時計回りの段数 (0..3) */
    public static int rotationFor(int[] face, int[] up) {
        int[] d = neg(face);
        int[] t = mapTopAtRotation0(face);
        int n = 0;
        while (!eq(t, up) && n < 4) { t = clockwise(d, t); n++; }
        if (n == 4) throw new IllegalArgumentException("up が面と垂直ではありません");
        return n;
    }

    /**
     * @param face 額縁の向き(クリックした面の法線。見る人の側を向く)
     * @param look 床・天井でのプレイヤーの水平な向き (壁では無視)
     */
    public static Tile tile(int[] face, int[] look, int rotateQuarter, int cols, int rows, int col, int row) {
        int[] up = imageUp(face, look, rotateQuarter);
        int[] right = clockwise(neg(face), up);
        int ru = rows - 1 - row; // 基準 = 左下
        return new Tile(right[0] * col + up[0] * ru, right[1] * col + up[1] * ru, right[2] * col + up[2] * ru,
                rotationFor(face, up));
    }
}
