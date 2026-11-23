package dev.lapius7.imagepaste;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** 保存済みの地図ピクセルを一度だけ描く。ピクセルが無いときは Store から読む。 */
public final class TileRenderer extends MapRenderer {
    private final Store store;
    private final int mapId;
    private byte[] pixels;
    private boolean done;

    public TileRenderer(Store store, int mapId, byte[] pixels) {
        super(false);
        this.store = store;
        this.mapId = mapId;
        this.pixels = pixels;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (done) return;
        if (pixels == null) pixels = store.loadTile(mapId);
        done = true;
        if (pixels == null) return;
        for (int y = 0; y < ImageProcessor.TILE; y++)
            for (int x = 0; x < ImageProcessor.TILE; x++)
                canvas.setPixel(x, y, pixels[y * ImageProcessor.TILE + x]);
        pixels = null; // メモリ節約。地図データ本体は map_N.dat に残る
    }
}
