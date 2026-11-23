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
import org.bukkit.event.player.*;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
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
    /** プレビュー中の1人分の状態 */
    private static final class Session {
        final BufferedImage img;
        final Opts o;
        final int maxW, maxH, maxTiles;
        long expires;
        double scroll;
        Session(BufferedImage img, Opts o, int maxW, int maxH, int maxTiles, long expires) {
            this.img = img; this.o = o; this.maxW = maxW; this.maxH = maxH; this.maxTiles = maxTiles; this.expires = expires;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
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
        loadPreviewCfg();
        getServer().getScheduler().runTaskTimer(this, this::tick, 10L, Math.max(1, getConfig().getInt("preview.interval-ticks", 4)));
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
                loadPreviewCfg();
                fetcher = new ImageFetcher(getConfig().getConfigurationSection("download") == null
                        ? new org.bukkit.configuration.MemoryConfiguration() : getConfig().getConfigurationSection("download"));
                msg(s, "reloaded", "設定を再読み込みしました");
            }
            case "cancel" -> {
                if (s instanceof Player p) {
                    boolean had = sessions.remove(p.getUniqueId()) != null;
                    msg(s, had ? "cancelled" : "nothing-pending", had ? "貼り付けを中止しました" : "待機中の貼り付けはありません");
                }
            }
            case "size" -> withSession(s, (p, se) -> {
                try {
                    if (a.length < 2) { msg(p, "bad-option", "オプションが不正です: {opt}", "opt", "size <w> [h]"); return; }
                    int w = Integer.parseInt(a[1]), h = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                    if (!applySize(p, se, w, h)) return;
                } catch (NumberFormatException ex) { msg(p, "bad-option", "オプションが不正です: {opt}", "opt", "size は整数"); }
            });
            case "rotate" -> withSession(s, (p, se) -> {
                if (a.length > 1) {
                    String err = Opts.set(se.o, "rotate", a[1]);
                    if (err != null) { msg(p, "bad-option", "オプションが不正です: {opt}", "opt", err); return; }
                } else se.o.rotate = (se.o.rotate + 90) % 360;
                touch(se);
            });
            case "flip" -> withSession(s, (p, se) -> {
                if (a.length > 1) {
                    String err = Opts.set(se.o, "flip", a[1]);
                    if (err != null) { msg(p, "bad-option", "オプションが不正です: {opt}", "opt", err); return; }
                } else se.o.flip = (se.o.flip + 1) % 4;
                touch(se);
            });
            case "confirm" -> withSession(s, (p, se) -> {
                RayTraceResult rr = p.rayTraceBlocks(previewDistance, FluidCollisionMode.NEVER);
                if (rr == null || rr.getHitBlock() == null || rr.getHitBlockFace() == null) { msg(p, "look-at-block", "貼りたいブロックを見てください"); return; }
                confirm(p, se, rr.getHitBlock(), rr.getHitBlockFace());
            });
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
        msg(s, "usage", "/imagepaste <画像URL> [width=N] [height=N] [quality=low|normal|high] ... | size <w> [h] | rotate | flip | confirm | cancel | undo | list | remove <id> | reload");
    }

    private void withSession(CommandSender s, java.util.function.BiConsumer<Player, Session> f) {
        if (!(s instanceof Player p)) { msg(s, "players-only", "プレイヤーのみ使えます"); return; }
        Session se = sessions.get(p.getUniqueId());
        if (se == null) { msg(s, "nothing-pending", "待機中の貼り付けはありません"); return; }
        f.accept(p, se);
        touch(se);
    }

    private void touch(Session se) {
        se.expires = System.currentTimeMillis() + getConfig().getLong("limits.pending-timeout-seconds", 120) * 1000;
    }

    /** サイズを指定。上限を超える場合は変更せず false */
    private boolean applySize(Player p, Session se, int w, int h) {
        if (w < 1 || h < 0) { msg(p, "bad-option", "オプションが不正です: {opt}", "opt", "サイズは1以上"); return false; }
        if (w > se.maxW || h > se.maxH) { msg(p, "too-large", "サイズが上限を超えています (最大 {w}x{h})", "w", se.maxW, "h", se.maxH); return false; }
        Opts t = new Opts(); t.width = w; t.height = h;
        int[] d = ImageProcessor.dims(se.img.getWidth(), se.img.getHeight(), t, se.maxW, se.maxH);
        if (d[0] * d[1] > se.maxTiles) { msg(p, "too-many-tiles", "タイル数が上限({n})を超えます", "n", se.maxTiles); return false; }
        se.o.width = w; se.o.height = h;
        touch(se);
        return true;
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
        sessions.remove(p.getUniqueId());
        msg(s, "downloading", "画像を取得中です...");
        final UUID id = p.getUniqueId();
        final int fw = maxW, fh = maxH, ft = maxTiles;
        final ImageFetcher f = fetcher;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String err = null;
            BufferedImage img = null;
            try {
                img = f.fetch(url);
            } catch (ImageFetcher.FetchException e) {
                err = e.getMessage();
            } catch (Throwable e) {
                getLogger().warning("画像取得に失敗: " + e);
                err = "処理中にエラーが発生しました";
            } finally {
                downloads.decrementAndGet(); busy.remove(id);
            }
            final String ferr = err; final BufferedImage fimg = img;
            getServer().getScheduler().runTask(this, () -> {
                Player pl = getServer().getPlayer(id);
                if (pl == null) return;
                if (ferr != null) { msg(pl, "error", "失敗: {reason}", "reason", ferr); return; }
                // タイル数が上限を超える初期サイズは、収まるまで縮める
                while (o.width > 1) {
                    int[] d = ImageProcessor.dims(fimg.getWidth(), fimg.getHeight(), o, fw, fh);
                    if (d[0] * d[1] <= ft) break;
                    o.width--; o.height = 0;
                }
                Session se = new Session(fimg, o, fw, fh, ft, 0);
                touch(se);
                sessions.put(id, se);
                msg(pl, "ready", "プレビュー中です。右クリック=確定 / 左クリック=キャンセル / F=回転 / Q=反転 / Shift+スクロール=拡縮");
            });
        });
    }

    // ---------- プレビュー ----------
    private int previewDistance = 10;
    private double edgeStep = 0.5, dustSize = 1.0;
    private Particle particle = Particle.DUST;
    private Color edgeColor, topColor, cornerColor, errorColor;
    private String actionbarFmt;
    private int scrollNotches = 1, scrollStep = 1;
    private boolean invertScroll;

    private static Color color(String hex, Color def) {
        try { return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)); } catch (RuntimeException e) { return def; }
    }

    private void loadPreviewCfg() {
        var c = getConfig();
        previewDistance = Math.max(2, c.getInt("preview.max-distance", 10));
        edgeStep = Math.max(0.1, c.getDouble("preview.edge-step", 0.5));
        dustSize = c.getDouble("preview.particle-size", 1.0);
        edgeColor = color(c.getString("preview.edge-color", "#55FFFF"), Color.AQUA);
        topColor = color(c.getString("preview.top-color", "#FFFF55"), Color.YELLOW);
        cornerColor = color(c.getString("preview.corner-color", "#FFFFFF"), Color.WHITE);
        errorColor = color(c.getString("preview.error-color", "#FF5555"), Color.RED);
        scrollNotches = Math.max(1, c.getInt("preview.scroll-sensitivity", 1));
        scrollStep = Math.max(1, c.getInt("preview.scroll-step", 1));
        invertScroll = c.getBoolean("preview.invert-scroll", false);
        actionbarFmt = c.getString("preview.actionbar", "&b{cols}x{rows}&7 ({tiles}タイル) &f向き {rotate}° 反転 {flip} &8| &7{status}");
        String pn = c.getString("preview.particle", "DUST").toLowerCase(Locale.ROOT);
        Particle pt = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(pn));
        particle = pt != null ? pt : Particle.DUST;
    }

    private void spawn(Player p, double x, double y, double z, Color col, float size) {
        Location l = new Location(p.getWorld(), x, y, z);
        if (particle == Particle.DUST) p.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(col, size));
        else p.spawnParticle(particle, l, 1, 0, 0, 0, 0);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (var en : sessions.entrySet()) {
            Player p = getServer().getPlayer(en.getKey());
            Session se = en.getValue();
            if (p == null || !p.isOnline()) { sessions.remove(en.getKey()); continue; }
            if (now > se.expires) { sessions.remove(en.getKey()); msg(p, "expired", "時間切れで貼り付け待ちを解除しました"); continue; }
            try { renderPreview(p, se); } catch (RuntimeException ex) { getLogger().warning("プレビュー描画に失敗: " + ex); }
        }
    }

    private void renderPreview(Player p, Session se) {
        Opts o = se.o;
        int[] dm = ImageProcessor.dims(se.img.getWidth(), se.img.getHeight(), o, se.maxW, se.maxH);
        int cols = dm[0], rows = dm[1];
        RayTraceResult rr = p.rayTraceBlocks(previewDistance, FluidCollisionMode.NEVER);
        Block hit = rr == null ? null : rr.getHitBlock();
        BlockFace face = rr == null ? null : rr.getHitBlockFace();
        String status;
        if (hit == null || face == null) {
            status = raw("preview-look", "ブロックを見てください");
        } else {
            int[] fv = {face.getModX(), face.getModY(), face.getModZ()};
            BlockFace lf = cardinal(p.getLocation().getYaw());
            int[] look = {lf.getModX(), 0, lf.getModZ()};
            int rq = o.rotate / 90;
            Block base = hit.getRelative(face);
            int bad = 0;
            for (int row = 0; row < rows; row++)
                for (int col = 0; col < cols; col++) {
                    Layout.Tile t = Layout.tile(fv, look, rq, cols, rows, col, row);
                    Block spot = base.getRelative(t.dx(), t.dy(), t.dz());
                    if (!canHang(spot, spot.getRelative(face.getOppositeFace()), face)) bad++;
                }
            draw(p, fv, look, rq, cols, rows, base, bad == 0);
            status = bad == 0 ? raw("preview-ok", "右クリックで確定") : raw("preview-blocked", "置けない枠があります ({n})").replace("{n}", String.valueOf(bad));
        }
        if (getConfig().getBoolean("preview.actionbar-enabled", true)) {
            String where = face == null ? "-" : face == BlockFace.UP ? "床" : face == BlockFace.DOWN ? "天井" : "壁";
            String t = actionbarFmt.replace("{cols}", "" + cols).replace("{rows}", "" + rows).replace("{tiles}", "" + cols * rows)
                    .replace("{rotate}", "" + o.rotate).replace("{flip}", new String[]{"なし", "左右", "上下", "左右+上下"}[o.flip])
                    .replace("{where}", where).replace("{status}", status);
            p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(t));
        }
    }

    /** 範囲の外周・四隅・上辺の印をパーティクルで描く */
    private void draw(Player p, int[] fv, int[] look, int rq, int cols, int rows, Block base, boolean ok) {
        Layout.Box bx = Layout.footprint(fv, look, rq, cols, rows);
        int[][] ax = Layout.axes(fv, look, rq);
        int[] up = ax[0], rt = ax[1];
        int[] mn = {base.getX() + bx.minX(), base.getY() + bx.minY(), base.getZ() + bx.minZ()};
        int[] mx = {base.getX() + bx.maxX(), base.getY() + bx.maxY(), base.getZ() + bx.maxZ()};
        double[] o = new double[3];
        double W = 0, H = 0;
        for (int i = 0; i < 3; i++) {
            double lo = mn[i], hi = mx[i] + 1;
            if (fv[i] != 0) o[i] = fv[i] > 0 ? lo + 0.03 : hi - 0.03;
            else if (rt[i] != 0) { o[i] = rt[i] > 0 ? lo : hi; W = hi - lo; }
            else { o[i] = up[i] > 0 ? lo : hi; H = hi - lo; }
        }
        Color main = ok ? edgeColor : errorColor, top = ok ? topColor : errorColor;
        float sz = (float) dustSize;
        for (double x = 0; x <= W + 1e-6; x += edgeStep) {
            pt(p, o, rt, up, x, 0, main, sz);
            pt(p, o, rt, up, x, H, top, sz);
        }
        for (double y = edgeStep; y < H - 1e-6; y += edgeStep) {
            pt(p, o, rt, up, 0, y, main, sz);
            pt(p, o, rt, up, W, y, main, sz);
        }
        for (double[] c : new double[][]{{0, 0}, {W, 0}, {0, H}, {W, H}})
            for (int k = 0; k < 3; k++) pt(p, o, rt, up, c[0] + (c[0] == 0 ? 0.08 * k : -0.08 * k), c[1] + (c[1] == 0 ? 0.08 * k : -0.08 * k), cornerColor, sz * 1.4f);
        // 上辺の外側に矢印 (上 = 画像の上)
        double cx = W / 2;
        for (double t = 0; t <= 1.0001; t += 0.1) {
            pt(p, o, rt, up, cx, H + 0.2 + 0.5 * t, top, sz);
            pt(p, o, rt, up, cx - 0.35 * (1 - t), H + 0.2 + 0.5 * t - 0.0, top, sz);
            pt(p, o, rt, up, cx + 0.35 * (1 - t), H + 0.2 + 0.5 * t - 0.0, top, sz);
        }
    }

    private void pt(Player p, double[] o, int[] rt, int[] up, double x, double y, Color c, float sz) {
        spawn(p, o[0] + rt[0] * x + up[0] * y, o[1] + rt[1] * x + up[1] * y, o[2] + rt[2] * x + up[2] * y, c, sz);
    }

    // ---------- 操作 ----------
    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Session se = sessions.get(p.getUniqueId());
        if (se == null) return;
        Action act = e.getAction();
        if (act == Action.PHYSICAL) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (act == Action.LEFT_CLICK_AIR || act == Action.LEFT_CLICK_BLOCK) {
            sessions.remove(p.getUniqueId());
            msg(p, "cancelled", "貼り付けを中止しました");
            return;
        }
        Block clicked = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (clicked == null) {
            RayTraceResult rr = p.rayTraceBlocks(previewDistance, FluidCollisionMode.NEVER);
            if (rr == null || rr.getHitBlock() == null || rr.getHitBlockFace() == null) { msg(p, "look-at-block", "貼りたいブロックを見てください"); return; }
            clicked = rr.getHitBlock(); face = rr.getHitBlockFace();
        }
        confirm(p, se, clicked, face);
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Session se = sessions.get(p.getUniqueId());
        if (se == null || !p.isSneaking()) return;
        e.setCancelled(true);
        int d = Math.floorMod(e.getNewSlot() - e.getPreviousSlot() + 4, 9) - 4; // -4..4
        se.scroll += (invertScroll ? d : -d); // ホイール上 (スロット番号が減る) = 拡大
        int steps = (int) (se.scroll / scrollNotches);
        if (steps == 0) return;
        se.scroll -= steps * scrollNotches;
        int nw = Math.max(1, se.o.width + steps * scrollStep);
        int nh = se.o.height > 0 ? Math.max(1, se.o.height + steps * scrollStep) : 0;
        applySize(p, se, Math.min(nw, se.maxW), Math.min(nh, se.maxH));
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Session se = sessions.get(e.getPlayer().getUniqueId());
        if (se == null) return;
        e.setCancelled(true);
        se.o.rotate = (se.o.rotate + 90) % 360;
        touch(se);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Session se = sessions.get(e.getPlayer().getUniqueId());
        if (se == null) return;
        e.setCancelled(true);
        se.o.flip = (se.o.flip + 1) % 4;
        touch(se);
    }

    /** 確定: 画像を非同期で減色し、終わったらその場所に貼る */
    private void confirm(Player p, Session se, Block clicked, BlockFace face) {
        if (!p.hasPermission("imagepaste.use")) { msg(p, "no-permission", "権限がありません"); return; }
        if (sessions.remove(p.getUniqueId(), se) == false) return;
        BlockFace lf = cardinal(p.getLocation().getYaw());
        final int[] look = {lf.getModX(), 0, lf.getModZ()};
        final UUID id = p.getUniqueId();
        msg(p, "processing", "画像を変換中です...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ImageProcessor.Result res = null;
            String err = null;
            try {
                res = ImageProcessor.process(se.img, se.o, se.maxW, se.maxH, se.maxTiles);
            } catch (IllegalArgumentException ex) {
                err = ex.getMessage();
            } catch (Throwable ex) {
                getLogger().warning("画像処理に失敗: " + ex);
                err = "処理中にエラーが発生しました";
            }
            final String ferr = err; final ImageProcessor.Result fres = res;
            getServer().getScheduler().runTask(this, () -> {
                Player pl = getServer().getPlayer(id);
                if (pl == null) return;
                if (ferr != null) { msg(pl, "error", "失敗: {reason}", "reason", ferr); return; }
                try {
                    place(pl, clicked, face, look, se.o, fres);
                } catch (RuntimeException ex) {
                    getLogger().warning("貼り付け失敗: " + ex);
                    msg(pl, "error", "失敗: {reason}", "reason", "内部エラー");
                }
            });
        });
    }

    private static BlockFace cardinal(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private void place(Player p, Block clicked, BlockFace face, int[] look, Opts o, ImageProcessor.Result r) {
        int cols = r.cols(), rows = r.rows();
        int[] fv = {face.getModX(), face.getModY(), face.getModZ()};
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
        sessions.remove(e.getPlayer().getUniqueId());
        lastUse.remove(e.getPlayer().getUniqueId());
    }

    // ---------- タブ補完 ----------
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("imagepaste.use")) return List.of();
        List<String> out = new ArrayList<>();
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            out.addAll(List.of("undo", "cancel", "confirm", "size", "rotate", "flip", "list", "remove", "help"));
            if (s.hasPermission("imagepaste.admin")) out.add("reload");
        } else if (a[0].equalsIgnoreCase("remove") && a.length == 2) {
            if (s instanceof Player p) store.byOwner(p.getUniqueId()).forEach(x -> out.add(x.id));
            else store.all().forEach(x -> out.add(x.id));
        } else if (a[0].equalsIgnoreCase("list") && a.length == 2) {
            if (s.hasPermission("imagepaste.admin")) out.add("all");
        } else if (a[0].equalsIgnoreCase("rotate") && a.length == 2) {
            out.addAll(List.of("0", "90", "180", "270"));
        } else if (a[0].equalsIgnoreCase("flip") && a.length == 2) {
            out.addAll(List.of("none", "h", "v", "both"));
        } else if (a[0].equalsIgnoreCase("size") && a.length <= 3) {
            out.addAll(List.of("1", "2", "4", "8"));
        } else if (List.of("undo", "cancel", "confirm", "reload", "remove", "list").contains(a[0].toLowerCase(Locale.ROOT))) {
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
