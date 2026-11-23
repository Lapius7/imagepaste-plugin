package dev.lapius7.imagepaste;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 期待値は Layout の式を使わず、方角の表から独立に作る。 */
class LayoutTest {
    static final int[] N = {0, 0, -1}, E = {1, 0, 0}, S = {0, 0, 1}, W = {-1, 0, 0}, UP = {0, 1, 0}, DOWN = {0, -1, 0};
    static final int[][] CARD = {N, E, S, W}; // コンパス時計回り (上から見て)

    static int idx(int[] v) { for (int i = 0; i < 4; i++) if (Arrays.equals(CARD[i], v)) return i; throw new IllegalArgumentException(Arrays.toString(v)); }
    static int[] cw(int[] v) { return CARD[(idx(v) + 1) % 4]; }   // 上から見て時計回り
    static int[] ccw(int[] v) { return CARD[(idx(v) + 3) % 4]; }  // 下から見ると時計回りはこちら
    static int[] neg(int[] v) { return new int[]{-v[0], -v[1], -v[2]}; }
    static int[] add(int[] a, int[] b) { return new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static int[] mul(int[] a, int k) { return new int[]{a[0] * k, a[1] * k, a[2] * k}; }

    /** 回転 k 段のとき地図の上が向く方向 (バニラの描画: 上向き面は北、下向き面は南が回転0) */
    static int[] mapTop(int[] face, int k) {
        if (face[1] == 0) { // 壁: 見る人から見て時計回り = 上 → 右手 → 下 → 左手
            int[] r = cw(neg(face));
            return new int[][]{UP, r, DOWN, neg(r)}[k];
        }
        int[] t = face[1] > 0 ? N : S;
        for (int i = 0; i < k; i++) t = face[1] > 0 ? cw(t) : ccw(t); // 床=上から時計回り, 天井=下から時計回り
        return t;
    }

    @Test
    void wallsAllDirectionsAndRotations() {
        for (int[] face : CARD) {
            int[] right0 = cw(neg(face)); // 見る人の右手
            for (int rot = 0; rot < 4; rot++) {
                int[] up = new int[][]{UP, right0, DOWN, neg(right0)}[rot];
                int[] right = new int[][]{right0, DOWN, neg(right0), UP}[rot]; // 上を時計回りに回すと右も回る
                check(face, new int[]{0, 0, 0}, rot, up, right);
            }
        }
    }

    @Test
    void footprintCoversAllTilesAndHasRightSize() {
        int[][] faces = {N, E, S, W, UP, DOWN};
        for (int[] face : faces) for (int[] look : CARD) for (int rot = 0; rot < 4; rot++) {
            int cols = 5, rows = 2;
            Layout.Box b = Layout.footprint(face, look, rot, cols, rows);
            for (int c = 0; c < cols; c++) for (int r = 0; r < rows; r++) {
                Layout.Tile t = Layout.tile(face, look, rot, cols, rows, c, r);
                assertTrue(t.dx() >= b.minX() && t.dx() <= b.maxX() && t.dy() >= b.minY() && t.dy() <= b.maxY()
                        && t.dz() >= b.minZ() && t.dz() <= b.maxZ());
            }
            int vol = (b.maxX() - b.minX() + 1) * (b.maxY() - b.minY() + 1) * (b.maxZ() - b.minZ() + 1);
            assertEquals(cols * rows, vol);
        }
    }

    @Test
    void floorAllPlayerDirectionsAndRotations() {
        for (int[] look : CARD) for (int rot = 0; rot < 4; rot++) {
            int[] up = look; for (int i = 0; i < rot; i++) up = cw(up);   // 床: 上=向き、rotate は上から時計回り
            int[] right = cw(up);                                           // 上から見て上の右はコンパス時計回り
            check(UP, look, rot, up, right);
        }
    }

    @Test
    void ceilingAllPlayerDirectionsAndRotations() {
        for (int[] look : CARD) for (int rot = 0; rot < 4; rot++) {
            int[] up = neg(look); for (int i = 0; i < rot; i++) up = ccw(up); // 下から見て時計回り = コンパス反時計
            int[] right = ccw(up);                                              // 下から見ると右はコンパス反時計
            check(DOWN, look, rot, up, right);
        }
    }

    private void check(int[] face, int[] look, int rot, int[] up, int[] right) {
        int cols = 4, rows = 3;
        for (int c = 0; c < cols; c++) for (int r = 0; r < rows; r++) {
            Layout.Tile t = Layout.tile(face, look, rot, cols, rows, c, r);
            int[] exp = add(mul(right, c), mul(up, rows - 1 - r));
            String m = "face=" + Arrays.toString(face) + " look=" + Arrays.toString(look) + " rot=" + rot;
            assertArrayEquals(exp, new int[]{t.dx(), t.dy(), t.dz()}, m);
            assertArrayEquals(up, mapTop(face, t.rotation()), m + " 地図の上");
        }
    }

    @Test
    void floorPlayerFacingNorthPutsTopAway() {
        // 北を向いて床に貼る: 上は北(奥)、右は東。最上段(r=0)は基準より北側。
        Layout.Tile top = Layout.tile(UP, N, 0, 2, 2, 0, 0), bottom = Layout.tile(UP, N, 0, 2, 2, 0, 1);
        assertEquals(-1, top.dz()); assertEquals(0, bottom.dz());
        assertEquals(0, top.rotation());
        assertEquals(1, Layout.tile(UP, N, 0, 2, 2, 1, 0).dx());
    }
}
