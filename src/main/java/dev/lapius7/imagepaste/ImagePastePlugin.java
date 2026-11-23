package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImagePastePlugin extends JavaPlugin implements Listener, TabExecutor {
    private record Pending(ImageProcessor.Result result, Opts opts, long expires) {}

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final AtomicInteger downloads = new AtomicInteger();
    private Store store;
    private ImageFetcher fetcher;
    private NamespacedKey pasteKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pasteKey = new NamespacedKey(this, "paste");
        try {
            store = new Store(getDataFolder().toPath().resolve("data"));
        } catch (IOException e) {
            getLogger().severe("データの読み込みに失敗: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        fetcher = new ImageFetcher(getConfig().getConfigurationSection("download") == null
                ? new org.bukkit.configuration.MemoryConfiguration() : getConfig().getConfigurationSection("download"));
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = Objects.requireNonNull(getCommand("imagepaste"));
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        getLogger().info("読み込み完了 (貼り付け " + store.all().size() + "件)");
    }

    // ---------- メッセージ ----------
    private String raw(String key, String def) { return getConfig().getString("messages." + key, def); }

    private void msg(CommandSender to, String key, String def, Object... kv) {
        String s = raw(key, def);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        to.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(raw("prefix", "&b[ImagePaste]&r ") + s));
    }

    // ---------- コマンド ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("imagepaste.use")) { msg(s, "no-permission", "権限がありません"); return true; }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { usage(s); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!s.hasPermission("imagepaste.admin")) { msg(s, "no-permission", "権限がありません"); return true; }
                reloadConfig();
                fetcher = new ImageFetcher(getConfig().getConfigurationSection("download") == null
                        ? new org.bukkit.configuration.MemoryConfiguration() : getConfig().getConfigurationSection("download"));
                msg(s, "reloaded", "設定を再読み込みしました");
            }
            case "cancel" -> {
                if (s instanceof Player p) {
                    boolean had = pending.remove(p.getUniqueId()) != null;
                    msg(s, had ? "cancelled" : "nothing-pending", had ? "貼り付けを中止しました" : "待機中の貼り付けはありません");
                }
            }
            case "undo" -> {
                if (!(s instanceof Player p)) { msg(s, "players-only", "プレイヤーのみ使えます"); return true; }
                Store.Paste last = store.lastOf(p.getUniqueId());
                if (last == null) { msg(s, "nothing-to-undo", "取り消せる貼り付けがありません"); return true; }
                removePaste(last);
                msg(s, "undone", "直前の貼り付けを削除しました");
            }
            case "list" -> {
                boolean all = a.length > 1 && a[1].equalsIgnoreCase("all") && s.hasPermission("imagepaste.admin");
                List<Store.Paste> l = all ? store.all() : (s instanceof Player p ? store.byOwner(p.getUniqueId()) : store.all());
                if (l.isEmpty()) { msg(s, "list-empty", "貼り付けはありません"); return true; }
                for (Store.Paste p : l)
                    s.sendMessage(Component.text(p.id + "  " + p.cols + "x" + p.rows + "  " + p.world + " ("
                            + p.frames.get(0).x() + "," + p.frames.get(0).y() + "," + p.frames.get(0).z() + ")  " + p.ownerName));
            }
            case "remove" -> {
                if (a.length < 2) { usage(s); return true; }
                Store.Paste p = store.find(a[1]);
                boolean admin = s.hasPermission("imagepaste.admin");
                if (p == null || (!admin && !(s instanceof Player pl && pl.getUniqueId().equals(p.owner)))) {
                    msg(s, "not-found", "その ID の貼り付けが見つかりません");
                    return true;
                }
                removePaste(p);
                msg(s, "removed", "削除しました: {id}", "id", p.id);
            }
            default -> startPaste(s, a);
        }
        return true;
    }

    private void usage(CommandSender s) {
        msg(s, "usage", "/imagepaste <画像URL> [width=N] [height=N] [mode=fit|fill|stretch] [quality=low|normal|high] [dither=..] ... | undo | cancel | list | remove <id> | reload");
    }

    private void startPaste(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "players-only", "プレイヤーのみ使えます"); return; }
        boolean bypass = p.hasPermission("imagepaste.bypass");
        String url = a[0];
        Opts o = Opts.fromConfig(getConfig());
        for (int i = 1; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq <= 0) { msg(s, "bad-option", "オプションが不正です: {opt}", "opt", a[i]); return; }
            String err = Opts.set(o, a[i].substring(0, eq), a[i].substring(eq + 1));
            if (err != null) { msg(s, "bad-option", "オプションが不正です: {opt}", "opt", err); return; }
        }
        int maxW = getConfig().getInt("limits.max-width", 16), maxH = getConfig().getInt("limits.max-height", 16);
        int maxTiles = getConfig().getInt("limits.max-tiles", 128);
        if (bypass) { maxW = maxH = 64; maxTiles = Integer.MAX_VALUE; }
        if (o.width > maxW || o.height > maxH) { msg(s, "too-large", "サイズが上限を超えています (最大 {w}x{h})", "w", maxW, "h", maxH); return; }
        if (o.height > 0 && o.width * o.height > maxTiles) { msg(s, "too-many-tiles", "タイル数が上限({n})を超えます", "n", maxTiles); return; }
        long cd = getConfig().getLong("limits.cooldown-seconds", 10) * 1000;
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (!bypass && last != null && now - last < cd) { msg(s, "cooldown", "{s}秒後に再実行できます", "s", (cd - (now - last) + 999) / 1000); return; }
        if (!busy.add(p.getUniqueId())) { msg(s, "busy", "取得中です。しばらくお待ちください"); return; }
        int maxDl = getConfig().getInt("download.max-concurrent", 3);
        if (downloads.incrementAndGet() > maxDl) {
            downloads.decrementAndGet(); busy.remove(p.getUniqueId());
            msg(s, "server-busy", "取得が混み合っています。少し待ってください");
            return;
        }
        lastUse.put(p.getUniqueId(), now);
        pending.remove(p.getUniqueId());
        msg(s, "downloading", "画像を取得中です...");
        final UUID id = p.getUniqueId();
        final int fw = maxW, fh = maxH, ft = maxTiles;
        final ImageFetcher f = fetcher;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String err = null;
            ImageProcessor.Result res = null;
            try {
                BufferedImage img = f.fetch(url);
                res = ImageProcessor.process(img, o, fw, fh, ft);
            } catch (ImageFetcher.FetchException | IllegalArgumentException e) {
                err = e.getMessage();
            } catch (Throwable e) {
                getLogger().warning("画像処理に失敗: " + e);
                err = "処理中にエラーが発生しました";
            } finally {
                downloads.decrementAndGet(); busy.remove(id);
            }
            final String ferr = err; final ImageProcessor.Result fres = res;
            getServer().getScheduler().runTask(this, () -> {
                Player pl = getServer().getPlayer(id);
                if (pl == null) return;
                if (ferr != null) { msg(pl, "error", "失敗: {reason}", "reason", ferr); return; }
                long ttl = getConfig().getLong("limits.pending-timeout-seconds", 60) * 1000;
                pending.put(id, new Pending(fres, o, System.currentTimeMillis() + ttl));
                msg(pl, "ready", "準備完了 ({cols}x{rows}タイル)。貼りたいブロックの面を右クリックしてください (/imagepaste cancel で中止)",
                        "cols", fres.cols(), "rows", fres.rows());
            });
        });
    }

    // ---------- 右クリックで貼り付け ----------
    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        pending.remove(p.getUniqueId());
        if (System.currentTimeMillis() > pd.expires()) { msg(p, "expired", "時間切れで貼り付け待ちを解除しました"); return; }
        if (!p.hasPermission("imagepaste.use")) { msg(p, "no-permission", "権限がありません"); return; }
        Block clicked = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (clicked == null) return;
        try {
            place(p, clicked, face, pd);
        } catch (RuntimeException ex) {
            getLogger().warning("貼り付け失敗: " + ex);
            msg(p, "error", "失敗: {reason}", "reason", "内部エラー");
        }
    }

    private static BlockFace cardinal(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private void place(Player p, Block clicked, BlockFace face, Pending pd) {
        ImageProcessor.Result r = pd.result();
        Opts o = pd.opts();
        int cols = r.cols(), rows = r.rows();
        int[] fv = {face.getModX(), face.getModY(), face.getModZ()};
        BlockFace lf = cardinal(p.getLocation().getYaw());
        int[] look = {lf.getModX(), 0, lf.getModZ()};
        int rq = o.rotate / 90;

        World w = clicked.getWorld();
        Block base = clicked.getRelative(face);
        boolean skip = o.blocked.equals("skip");
        List<int[]> cells = new ArrayList<>(); // c, r
        int bad = 0;
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < cols; col++) {
                Layout.Tile lt = Layout.tile(fv, look, rq, cols, rows, col, row);
                Block spot = base.getRelative(lt.dx(), lt.dy(), lt.dz());
                Block back = spot.getRelative(face.getOppositeFace());
                if (!canHang(spot, back, face)) { bad++; continue; }
                cells.add(new int[]{col, row});
            }
        if (bad > 0 && !skip) { msg(p, "blocked", "置き場所が埋まっているため中止しました (問題の枠: {n})", "n", bad); return; }
        if (cells.isEmpty()) { msg(p, "blocked", "置き場所が埋まっているため中止しました (問題の枠: {n})", "n", bad); return; }

        String pid = store.newId();
        List<Store.Frame> frames = new ArrayList<>();
        List<int[]> placed = new ArrayList<>();
        try {
            for (int[] cell : cells) {
                int col = cell[0], row = cell[1];
                byte[] px = r.tiles()[row * cols + col];
                MapView view = Bukkit.createMap(w);
                view.getRenderers().forEach(view::removeRenderer);
                view.setTrackingPosition(false);
                view.setUnlimitedTracking(false);
                view.addRenderer(new TileRenderer(store, view.getId(), px));
                ItemStack item = new ItemStack(Material.FILLED_MAP);
                MapMeta mm = (MapMeta) item.getItemMeta();
                mm.setMapView(view);
                item.setItemMeta(mm);
                Layout.Tile lt = Layout.tile(fv, look, rq, cols, rows, col, row);
                Block spot = base.getRelative(lt.dx(), lt.dy(), lt.dz());
                final int steps = lt.rotation();
                Class<? extends ItemFrame> type = o.glow ? GlowItemFrame.class : ItemFrame.class;
                ItemFrame fr = w.spawn(spot.getLocation(), type, ent -> {
                    ent.setFacingDirection(face, true);
                    ent.setVisible(!o.invisible);
                    ent.setFixed(o.fixed);
                    ent.setItemDropChance(0f);
                    ent.setItem(item, false);
                    if (steps > 0) ent.setRotation(Rotation.values()[steps]);
                    ent.getPersistentDataContainer().set(pasteKey, PersistentDataType.STRING, pid);
                });
                frames.add(new Store.Frame(fr.getUniqueId(), spot.getX(), spot.getY(), spot.getZ(), view.getId()));
                placed.add(new int[]{view.getId(), row * cols + col});
            }
        } catch (RuntimeException ex) {
            for (Store.Frame f : frames) { Entity en = Bukkit.getEntity(f.uuid()); if (en != null) en.remove(); }
            throw ex;
        }
        Store.Paste paste = new Store.Paste(pid, p.getUniqueId(), p.getName(), w.getName(), System.currentTimeMillis(), cols, rows, frames);
        store.add(paste);
        byte[][] tiles = r.tiles();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            for (int[] pl : placed) store.saveTile(pl[0], tiles[pl[1]]);
        });
        msg(p, "placed", "貼り付けました ({cols}x{rows}タイル, 地図{count}枚)。/imagepaste undo で取り消せます",
                "cols", cols, "rows", rows, "count", frames.size());
        if (bad > 0) msg(p, "skipped", "{n}枠は置けないため飛ばしました", "n", bad);
    }

    private boolean canHang(Block spot, Block back, BlockFace face) {
        if (!back.getType().isSolid()) return false;
        Material m = spot.getType();
        if (!(m.isAir() || spot.isPassable()) || spot.isLiquid()) return false;
        if (!spot.getWorld().getNearbyEntities(spot.getBoundingBox().expand(-0.01), x -> x instanceof Hanging).isEmpty()) return false;
        return true;
    }

    // ---------- 削除 ----------
    private void removePaste(Store.Paste p) {
        World w = Bukkit.getWorld(p.world);
        if (w != null) {
            for (Store.Frame f : p.frames) {
                Entity en = Bukkit.getEntity(f.uuid());
                if (en == null) {
                    w.getChunkAt(f.x() >> 4, f.z() >> 4); // チャンクを読み込んで探す
                    en = Bukkit.getEntity(f.uuid());
                }
                if (en instanceof ItemFrame fr) { fr.setFixed(false); fr.setItem(null, false); fr.remove(); }
            }
        }
        store.remove(p);
    }

    // ---------- 地図の再接続 (再起動後) ----------
    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        MapView v = e.getMap();
        if (!store.knowsMap(v.getId())) return;
        v.getRenderers().forEach(v::removeRenderer);
        v.addRenderer(new TileRenderer(store, v.getId(), null));
        v.setTrackingPosition(false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
        lastUse.remove(e.getPlayer().getUniqueId());
    }

    // ---------- タブ補完 ----------
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("imagepaste.use")) return List.of();
        List<String> out = new ArrayList<>();
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            out.addAll(List.of("undo", "cancel", "list", "remove", "help"));
            if (s.hasPermission("imagepaste.admin")) out.add("reload");
        } else if (a[0].equalsIgnoreCase("remove") && a.length == 2) {
            if (s instanceof Player p) store.byOwner(p.getUniqueId()).forEach(x -> out.add(x.id));
            else store.all().forEach(x -> out.add(x.id));
        } else if (a[0].equalsIgnoreCase("list") && a.length == 2) {
            if (s.hasPermission("imagepaste.admin")) out.add("all");
        } else if (List.of("undo", "cancel", "reload", "remove", "list").contains(a[0].toLowerCase(Locale.ROOT))) {
            return List.of();
        } else {
            int eq = cur.indexOf('=');
            if (eq < 0) { for (String k : Opts.KEYS) out.add(k + "="); }
            else {
                String k = cur.substring(0, eq);
                for (String v : Opts.valuesFor(k)) out.add(k + "=" + v);
            }
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }
}
