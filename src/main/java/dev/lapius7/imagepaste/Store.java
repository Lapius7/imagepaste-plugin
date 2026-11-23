package dev.lapius7.imagepaste;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 貼り付けの記録 (placements.yml) と、地図タイルのピクセル (tiles/ID.bin.gz) を保存する。 */
public final class Store {
    /** face / rot は再生成に使う (旧データは null / 0) */
    @com.github.bsideup.jabel.Desugar
    public record Frame(UUID uuid, int x, int y, int z, int mapId, String face, int rot) {
        public Frame(UUID uuid, int x, int y, int z, int mapId) { this(uuid, x, y, z, mapId, null, 0); }
        public Frame withUuid(UUID u) { return new Frame(u, x, y, z, mapId, face, rot); }
    }

    public static final class Paste {
        public final String id;
        public final UUID owner;
        public final String ownerName, world;
        public final long time;
        public final int cols, rows;
        public final List<Frame> frames;
        public final boolean invisible, glow, fixed;

        public Paste(String id, UUID owner, String ownerName, String world, long time, int cols, int rows, List<Frame> frames,
                     boolean invisible, boolean glow, boolean fixed) {
            this.id = id; this.owner = owner; this.ownerName = ownerName; this.world = world;
            this.time = time; this.cols = cols; this.rows = rows; this.frames = frames;
            this.invisible = invisible; this.glow = glow; this.fixed = fixed;
        }

        public Paste(String id, UUID owner, String ownerName, String world, long time, int cols, int rows, List<Frame> frames) {
            this(id, owner, ownerName, world, time, cols, rows, frames, true, false, true);
        }
    }

    private final Path dir, tiles, file;
    private final List<Paste> pastes = new ArrayList<>();
    private final Set<Integer> mapIds = new HashSet<>();

    public Store(Path dataDir) throws IOException {
        this.dir = dataDir;
        this.tiles = dataDir.resolve("tiles");
        this.file = dataDir.resolve("placements.yml");
        Files.createDirectories(tiles);
        load();
    }

    private void load() {
        pastes.clear(); mapIds.clear();
        if (!Files.exists(file)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection s = y.getConfigurationSection("pastes");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection p = s.getConfigurationSection(id);
            if (p == null) continue;
            List<Frame> fr = new ArrayList<>();
            for (String line : p.getStringList("frames")) {
                String[] a = line.split(",");
                if (a.length != 5 && a.length != 7) continue;
                try {
                    fr.add(new Frame(UUID.fromString(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
                            a.length == 7 ? a[5] : null, a.length == 7 ? Integer.parseInt(a[6]) : 0));
                    mapIds.add(Integer.parseInt(a[4]));
                } catch (IllegalArgumentException ignored) {}
            }
            try {
                pastes.add(new Paste(id, UUID.fromString(p.getString("owner", "")), p.getString("owner-name", "?"),
                        p.getString("world", "world"), p.getLong("time"), p.getInt("cols"), p.getInt("rows"), fr,
                        p.getBoolean("invisible", true), p.getBoolean("glow", false), p.getBoolean("fixed", true)));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Paste p : pastes) {
            String k = "pastes." + p.id + ".";
            y.set(k + "owner", p.owner.toString());
            y.set(k + "owner-name", p.ownerName);
            y.set(k + "world", p.world);
            y.set(k + "time", p.time);
            y.set(k + "cols", p.cols);
            y.set(k + "rows", p.rows);
            y.set(k + "invisible", p.invisible);
            y.set(k + "glow", p.glow);
            y.set(k + "fixed", p.fixed);
            List<String> l = new ArrayList<>();
            for (Frame f : p.frames) l.add(f.uuid() + "," + f.x() + "," + f.y() + "," + f.z() + "," + f.mapId()
                    + (f.face() != null ? "," + f.face() + "," + f.rot() : ""));
            y.set(k + "frames", l);
        }
        try {
            Path tmp = dir.resolve("placements.yml.tmp");
            y.save(tmp.toFile());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void add(Paste p) {
        pastes.add(p);
        for (Frame f : p.frames) mapIds.add(f.mapId);
        save();
    }

    public synchronized void remove(Paste p) {
        pastes.remove(p);
        for (Frame f : p.frames) { mapIds.remove(f.mapId); try { Files.deleteIfExists(tileFile(f.mapId)); } catch (IOException ignored) {} }
        save();
    }

    public synchronized boolean knowsMap(int id) { return mapIds.contains(id); }

    public synchronized List<Paste> all() { return new ArrayList<>(pastes); }

    public synchronized List<Paste> byOwner(UUID owner) {
        List<Paste> l = new ArrayList<>();
        for (Paste p : pastes) if (p.owner.equals(owner)) l.add(p);
        return l;
    }

    public synchronized Paste find(String id) {
        for (Paste p : pastes) if (p.id.equalsIgnoreCase(id)) return p;
        return null;
    }

    public synchronized Paste lastOf(UUID owner) {
        Paste last = null;
        for (Paste p : pastes) if (p.owner.equals(owner)) last = p;
        return last;
    }

    public synchronized String newId() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        Random r = new Random();
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            if (find(sb.toString()) == null) return sb.toString();
        }
    }

    /** 額縁の UUID から貼り付けを探す */
    public synchronized Paste byFrame(UUID frame) {
        for (Paste p : pastes) for (Frame f : p.frames) if (f.uuid().equals(frame)) return p;
        return null;
    }

    /** 1枠分の記録を外す。全部なくなったら貼り付け自体も消す (戻り値 true) */
    public synchronized boolean dropFrame(Paste p, Frame f) {
        p.frames.remove(f);
        mapIds.remove(f.mapId());
        try { Files.deleteIfExists(tileFile(f.mapId())); } catch (IOException ignored) {}
        boolean empty = p.frames.isEmpty();
        if (empty) pastes.remove(p);
        save();
        return empty;
    }

    public synchronized void replaceFrame(Paste p, Frame old, Frame nu) {
        int i = p.frames.indexOf(old);
        if (i >= 0) p.frames.set(i, nu);
        save();
    }

    /** 記録にない地図タイルのファイルを消す。消した数を返す */
    public synchronized int purgeOrphanTiles() {
        int n = 0;
        try (var st = Files.list(tiles)) {
            for (Path f : (Iterable<Path>) st::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".bin.gz")) continue;
                try {
                    if (!mapIds.contains(Integer.parseInt(name.substring(0, name.length() - 7)))) { Files.deleteIfExists(f); n++; }
                } catch (NumberFormatException | IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return n;
    }

    private Path tileFile(int mapId) { return tiles.resolve(mapId + ".bin.gz"); }

    public void saveTile(int mapId, byte[] px) {
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(tileFile(mapId)))) {
            o.write(px);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] loadTile(int mapId) {
        Path f = tileFile(mapId);
        if (!Files.exists(f)) return null;
        try (InputStream i = new GZIPInputStream(Files.newInputStream(f))) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) bo.write(buf, 0, n);
            byte[] b = bo.toByteArray();
            return b.length == ImageProcessor.TILE * ImageProcessor.TILE ? b : null;
        } catch (IOException e) {
            return null;
        }
    }
}
