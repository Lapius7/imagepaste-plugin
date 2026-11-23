package dev.lapius7.imagepaste;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.RayTraceResult;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ImagePastePlugin extends JavaPlugin implements Listener {
    /** プレビューが狙っている場所 */
    record Target(Block block, BlockFace face, int[] look) {}

    /** プレビュー中の1人分の状態 */
    static final class Session {
        final BufferedImage img;
        final Opts o;
        final int maxW, maxH, maxTiles;
        long expires;
        double scroll;
        Target last;
        boolean confirming;
        BossBar bar;
        Scoreboard prevBoard;
        Session(BufferedImage img, Opts o, int maxW, int maxH, int maxTiles) {
            this.img = img; this.o = o; this.maxW = maxW; this.maxH = maxH; this.maxTiles = maxTiles;
        }
    }

    enum OnBreak { PROTECT, REMOVE_ALL, REGENERATE }

    final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Map<String, Long> notified = new ConcurrentHashMap<>();
    private final AtomicInteger downloads = new AtomicInteger();
    Store store;
    Lang lang;
    ImageFetcher fetcher;
    NamespacedKey pasteKey, markerKey;
    OnBreak onBreak = OnBreak.PROTECT;
    private Guard guard;
    private org.bukkit.scheduler.BukkitTask ticker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pasteKey = new NamespacedKey(this, "paste");
        markerKey = new NamespacedKey(this, "marker");
        lang = new Lang(this);
        try {
            store = new Store(getDataFolder().toPath().resolve("data"));
        } catch (IOException e) {
            getLogger().severe("データの読み込みに失敗: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        fetcher = newFetcher();
        guard = new Guard(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(guard, this);
        PluginCommand cmd = Objects.requireNonNull(getCommand("imagepaste"));
        Commands c = new Commands(this);
        cmd.setExecutor(c);
        cmd.setTabCompleter(c);
        loadCfg();
        int purged = store.purgeOrphanTiles();
        if (purged > 0) getLogger().info("記録にない地図データを " + purged + "件 削除しました");
        guard.sweepLoaded();
        for (Player p : getServer().getOnlinePlayers()) sweepMarkers(p);
        getLogger().info("読み込み完了 (貼り付け " + store.all().size() + "件, 破損時: " + onBreak + ")");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) endSession(id);
        if (ticker != null) ticker.cancel();
    }

    ImageFetcher newFetcher() {
        ConfigurationSection d = getConfig().getConfigurationSection("download");
        return new ImageFetcher(d == null ? new org.bukkit.configuration.MemoryConfiguration() : d);
    }

    void reloadAll() {
        reloadConfig();
        lang.reload();
        fetcher = newFetcher();
        loadCfg();
    }

    // ---------- 設定 ----------
    private int previewDistance = 64;
    private double edgeStep = 0.5, dustSize = 1.0;
    private Particle particle = Particle.DUST;
    private Color edgeColor, topColor, cornerColor, errorColor;
    private int scrollNotches = 1, scrollStep = 1;
    private boolean invertScroll, actionbarOn = true, airClickItem = true;
    private String guideMode = "bossbar";

    private static Color color(String hex, Color def) {
        try { return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)); } catch (RuntimeException e) { return def; }
    }

    private Particle particleOf(String name, Particle def) {
        if (name == null) return def;
        Particle pt = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        return pt != null ? pt : def;
    }

    private void loadCfg() {
        var c = getConfig();
        previewDistance = Math.max(2, c.getInt("preview.max-distance", 64));
        edgeStep = Math.max(0.1, c.getDouble("preview.edge-step", 0.5));
        dustSize = c.getDouble("preview.particle-size", 1.0);
        edgeColor = color(c.getString("preview.edge-color", "#55FFFF"), Color.AQUA);
        topColor = color(c.getString("preview.top-color", "#FFFF55"), Color.YELLOW);
        cornerColor = color(c.getString("preview.corner-color", "#FFFFFF"), Color.WHITE);
        errorColor = color(c.getString("preview.error-color", "#FF5555"), Color.RED);
        scrollNotches = Math.max(1, c.getInt("preview.scroll-sensitivity", 1));
        scrollStep = Math.max(1, c.getInt("preview.scroll-step", 1));
        invertScroll = c.getBoolean("preview.invert-scroll", false);
        actionbarOn = c.getBoolean("preview.actionbar-enabled", true);
        airClickItem = c.getBoolean("preview.air-click-item", true);
        guideMode = c.getString("preview.guide.mode", "bossbar").toLowerCase(Locale.ROOT);
        particle = particleOf(c.getString("preview.particle", "DUST"), Particle.DUST);
        try {
            onBreak = OnBreak.valueOf(c.getString("protection.on-break", "protect").toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) { onBreak = OnBreak.PROTECT; }
        if (ticker != null) ticker.cancel();
        ticker = getServer().getScheduler().runTaskTimer(this, this::tick, 10L, Math.max(1, c.getInt("preview.interval-ticks", 4)));
    }

    // ---------- プレビュー開始 ----------
    void withSession(org.bukkit.command.CommandSender s, java.util.function.BiConsumer<Player, Session> f) {
        if (!(s instanceof Player p)) { lang.send(s, "players-only"); return; }
        Session se = sessions.get(p.getUniqueId());
        if (se == null) { lang.send(s, "nothing-pending"); return; }
        f.accept(p, se);
        touch(se);
    }

    void touch(Session se) {
        se.expires = System.currentTimeMillis() + getConfig().getLong("limits.pending-timeout-seconds", 120) * 1000;
    }

    /** サイズを指定。上限を超える場合は変更せず false */
    boolean applySize(Player p, Session se, int w, int h) {
        if (w < 1 || h < 0) { lang.send(p, "bad-option", "opt", "サイズは1以上"); return false; }
        if (w > se.maxW || h > se.maxH) { lang.send(p, "too-large", "maxw", se.maxW, "maxh", se.maxH); return false; }
        Opts t = new Opts(); t.width = w; t.height = h;
        int[] d = ImageProcessor.dims(se.img.getWidth(), se.img.getHeight(), t, se.maxW, se.maxH);
        if (d[0] * d[1] > se.maxTiles) { lang.send(p, "too-many-tiles", "max", se.maxTiles); return false; }
        se.o.width = w; se.o.height = h;
        touch(se);
        return true;
    }

    void startPaste(Player p, String[] a) {
        boolean bypass = p.hasPermission("imagepaste.bypass");
        String url = a[0];
        Opts o = Opts.fromConfig(getConfig());
        for (int i = 1; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            if (eq <= 0) { lang.send(p, "bad-option", "opt", a[i]); return; }
            String err = Opts.set(o, a[i].substring(0, eq), a[i].substring(eq + 1));
            if (err != null) { lang.send(p, "bad-option", "opt", err); return; }
        }
        int maxW = getConfig().getInt("limits.max-width", 16), maxH = getConfig().getInt("limits.max-height", 16);
        int maxTiles = getConfig().getInt("limits.max-tiles", 128);
        if (bypass) { maxW = maxH = 64; maxTiles = Integer.MAX_VALUE; }
        if (o.width > maxW || o.height > maxH) { lang.send(p, "too-large", "maxw", maxW, "maxh", maxH); return; }
        long cd = getConfig().getLong("limits.cooldown-seconds", 10) * 1000;
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (!bypass && last != null && now - last < cd) { lang.send(p, "cooldown", "sec", (cd - (now - last) + 999) / 1000); return; }
        if (!busy.add(p.getUniqueId())) { lang.send(p, "busy"); return; }
        int maxDl = getConfig().getInt("download.max-concurrent", 3);
        if (downloads.incrementAndGet() > maxDl) {
            downloads.decrementAndGet(); busy.remove(p.getUniqueId());
            lang.send(p, "server-busy");
            return;
        }
        lastUse.put(p.getUniqueId(), now);
        endSession(p.getUniqueId());
        lang.send(p, "downloading");
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
                if (ferr != null) { lang.send(pl, "error", "reason", ferr); return; }
                while (o.width > 1) {
                    int[] d = ImageProcessor.dims(fimg.getWidth(), fimg.getHeight(), o, fw, fh);
                    if (d[0] * d[1] <= ft) break;
                    o.width--; o.height = 0;
                }
                Session se = new Session(fimg, o, fw, fh, ft);
                touch(se);
                sessions.put(id, se);
                startGuide(pl, se);
                ensureMarker(pl);
                lang.send(pl, "ready");
            });
        });
    }

    /** プレビューを終える。表示と一時アイテムを片付ける */
    void endSession(UUID id) {
        Session se = sessions.remove(id);
        if (se == null) return;
        Player p = getServer().getPlayer(id);
        if (p == null) return;
        if (se.bar != null) p.hideBossBar(se.bar);
        if (se.prevBoard != null) p.setScoreboard(se.prevBoard);
        sweepMarkers(p);
        if (actionbarOn) p.sendActionBar(Component.empty());
    }

    // ---------- 操作説明 (ボスバー / スコアボード / チャット) ----------
    private void startGuide(Player p, Session se) {
        List<String> lines = lang.list("guide-lines");
        if (!guideMode.equals("none") && !guideMode.equals("chat")) {
            p.sendMessage(lang.c("guide-title"));
            for (String l : lines) p.sendMessage(lang.parse(l));
        }
        switch (guideMode) {
            case "bossbar" -> {
                Component t = Component.empty();
                for (int i = 0; i < lines.size(); i++) {
                    if (i > 0) t = t.append(Component.text("  |  ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
                    t = t.append(lang.parse(lines.get(i)));
                }
                BossBar.Color col = BossBar.Color.BLUE;
                BossBar.Overlay ov = BossBar.Overlay.PROGRESS;
                try { col = BossBar.Color.valueOf(getConfig().getString("preview.guide.bossbar-color", "BLUE").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
                try { ov = BossBar.Overlay.valueOf(getConfig().getString("preview.guide.bossbar-overlay", "PROGRESS").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
                se.bar = BossBar.bossBar(t, 1f, col, ov);
                p.showBossBar(se.bar);
            }
            case "scoreboard" -> {
                se.prevBoard = p.getScoreboard();
                Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
                Objective ob = sb.registerNewObjective("ipaste", Criteria.DUMMY, lang.c("guide-title"));
                ob.setDisplaySlot(DisplaySlot.SIDEBAR);
                ob.numberFormat(NumberFormat.blank());
                for (int i = 0; i < lines.size(); i++) {
                    var sc = ob.getScore("§" + Integer.toHexString(i));
                    sc.setScore(lines.size() - i);
                    sc.customName(lang.parse(lines.get(i)));
                }
                p.setScoreboard(sb);
            }
            case "chat" -> {
                p.sendMessage(lang.c("guide-title"));
                for (String l : lines) p.sendMessage(lang.parse(l));
            }
            default -> {}
        }
    }

    // ---------- 空中クリック用の一時アイテム ----------
    boolean isMarker(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    void sweepMarkers(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isMarker(inv.getItem(i))) inv.setItem(i, null);
        if (isMarker(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    /** 素手のままだと空中の右クリックが届かないため、持ち手が空のときだけ一時アイテムを持たせる */
    private void ensureMarker(Player p) {
        if (!airClickItem) return;
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (isMarker(hand)) return;
        sweepMarkers(p);
        if (!hand.getType().isAir()) return;
        ItemStack m = new ItemStack(Material.PAPER);
        var meta = m.getItemMeta();
        meta.displayName(lang.c("marker-name"));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        m.setItemMeta(meta);
        inv.setItemInMainHand(m);
    }

    // ---------- プレビュー描画 ----------
    private void spawn(Player p, double x, double y, double z, Color col, float size) {
        Location l = new Location(p.getWorld(), x, y, z);
        if (particle == Particle.DUST)
            p.getWorld().spawnParticle(Particle.DUST, List.of(p), null, x, y, z, 1, 0, 0, 0, 0, new Particle.DustOptions(col, size), true);
        else if (particle.getDataType() == Void.class)
            p.getWorld().spawnParticle(particle, List.of(p), null, x, y, z, 1, 0, 0, 0, 0, null, true);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (var en : sessions.entrySet()) {
            Player p = getServer().getPlayer(en.getKey());
            Session se = en.getValue();
            if (p == null || !p.isOnline()) { endSession(en.getKey()); continue; }
            if (now > se.expires) { endSession(en.getKey()); lang.send(p, "expired"); continue; }
            try { ensureMarker(p); renderPreview(p, se); } catch (RuntimeException ex) { getLogger().warning("プレビュー描画に失敗: " + ex); }
        }
    }

    /** いま見ているブロックの面を調べ、あれば狙いを更新する。空振りなら前回の狙いを残す */
    private Target refresh(Player p, Session se) {
        RayTraceResult rr = p.rayTraceBlocks(previewDistance, FluidCollisionMode.NEVER);
        if (rr != null && rr.getHitBlock() != null && rr.getHitBlockFace() != null) {
            BlockFace lf = cardinal(p.getLocation().getYaw());
            se.last = new Target(rr.getHitBlock(), rr.getHitBlockFace(), new int[]{lf.getModX(), 0, lf.getModZ()});
            return se.last;
        }
        return null;
    }

    private void renderPreview(Player p, Session se) {
        Opts o = se.o;
        int[] dm = ImageProcessor.dims(se.img.getWidth(), se.img.getHeight(), o, se.maxW, se.maxH);
        int cols = dm[0], rows = dm[1];
        boolean aimed = refresh(p, se) != null;
        Target t = se.last;
        Component status;
        String where = "where-none";
        if (t == null) {
            status = lang.c("preview-look");
        } else {
            BlockFace face = t.face();
            int[] fv = {face.getModX(), face.getModY(), face.getModZ()};
            int rq = o.rotate / 90;
            Block base = t.block().getRelative(face);
            int bad = 0;
            for (int row = 0; row < rows; row++)
                for (int col = 0; col < cols; col++) {
                    Layout.Tile lt = Layout.tile(fv, t.look(), rq, cols, rows, col, row);
                    Block spot = base.getRelative(lt.dx(), lt.dy(), lt.dz());
                    if (!canHang(spot, spot.getRelative(face.getOppositeFace()), face)) bad++;
                }
            draw(p, fv, t.look(), rq, cols, rows, base, bad == 0);
            status = bad == 0 ? lang.c("preview-ok") : lang.c("preview-blocked", "count", bad);
            where = face == BlockFace.UP ? "where-floor" : face == BlockFace.DOWN ? "where-ceiling" : "where-wall";
        }
        if (actionbarOn) {
            String[] fl = {"flip-none", "flip-h", "flip-v", "flip-both"};
            p.sendActionBar(lang.c("actionbar", "cols", cols, "rows", rows, "tiles", cols * rows, "rotate", o.rotate,
                    "flip", lang.c(fl[o.flip]), "where", lang.c(where), "status", status));
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
            endSession(p.getUniqueId());
            lang.send(p, "cancelled");
            return;
        }
        confirm(p, se);
    }

    /** 左クリック (腕を振る動作) は、空中でも PlayerInteractEvent が飛ばない環境の保険 */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        Session se = sessions.get(p.getUniqueId());
        if (se == null || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        // PlayerInteractEvent が先に処理して終了済みなら se は残っていない
        endSession(p.getUniqueId());
        lang.send(p, "cancelled");
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Session se = sessions.get(p.getUniqueId());
        if (se == null) return;
        if (!p.isSneaking()) { getServer().getScheduler().runTask(this, () -> { if (sessions.containsKey(p.getUniqueId())) ensureMarker(p); }); return; }
        e.setCancelled(true);
        int d = Math.floorMod(e.getNewSlot() - e.getPreviousSlot() + 4, 9) - 4;
        se.scroll += (invertScroll ? d : -d);
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
        if (se == null && !isMarker(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        if (se == null) return;
        se.o.flip = (se.o.flip + 1) % 4;
        touch(se);
    }

    @EventHandler
    public void onInvClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (isMarker(e.getCurrentItem()) || isMarker(e.getCursor())
                || (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player pl
                    && isMarker(pl.getInventory().getItem(e.getHotbarButton())))) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { sweepMarkers(e.getPlayer()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isMarker);
        endSession(e.getEntity().getUniqueId());
    }

    /** 確定: 今のプレビュー位置で、画像を非同期で減色してから貼る */
    void confirm(Player p, Session se) {
        if (!p.hasPermission("imagepaste.use")) { lang.send(p, "no-permission"); return; }
        refresh(p, se);
        Target t = se.last;
        if (t == null) { lang.send(p, "look-at-block"); return; }
        if (se.confirming) return;
        se.confirming = true;
        final UUID id = p.getUniqueId();
        lang.send(p, "processing");
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
                if (sessions.get(id) != se) return; // 変換中に中止された
                endSession(id);
                Player pl = getServer().getPlayer(id);
                if (pl == null) return;
                if (ferr != null) { lang.send(pl, "error", "reason", ferr); return; }
                try {
                    place(pl, t.block(), t.face(), t.look(), se.o, fres);
                } catch (RuntimeException ex) {
                    getLogger().warning("貼り付け失敗: " + ex);
                    lang.send(pl, "error", "reason", "内部エラー");
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

    private ItemStack mapItem(MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta mm = (MapMeta) item.getItemMeta();
        mm.setMapView(view);
        item.setItemMeta(mm);
        return item;
    }

    private ItemFrame spawnFrame(World w, Block spot, BlockFace face, int steps, boolean glow, boolean invisible, boolean fixed,
                                 ItemStack item, String pid) {
        Class<? extends ItemFrame> type = glow ? GlowItemFrame.class : ItemFrame.class;
        return w.spawn(spot.getLocation(), type, ent -> {
            ent.setFacingDirection(face, true);
            ent.setVisible(!invisible);
            ent.setFixed(fixed);
            ent.setItemDropChance(0f);
            ent.setItem(item, false);
            if (steps > 0) ent.setRotation(Rotation.values()[steps]);
            ent.getPersistentDataContainer().set(pasteKey, PersistentDataType.STRING, pid);
        });
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
        if ((bad > 0 && !skip) || cells.isEmpty()) { lang.send(p, "blocked", "count", bad); return; }

        String pid = store.newId();
        List<Store.Frame> frames = new ArrayList<>();
        List<int[]> placed = new ArrayList<>();
        List<Location> centers = new ArrayList<>();
        try {
            for (int[] cell : cells) {
                int col = cell[0], row = cell[1];
                byte[] px = r.tiles()[row * cols + col];
                MapView view = Bukkit.createMap(w);
                view.getRenderers().forEach(view::removeRenderer);
                view.setTrackingPosition(false);
                view.setUnlimitedTracking(false);
                view.addRenderer(new TileRenderer(store, view.getId(), px));
                Layout.Tile lt = Layout.tile(fv, look, rq, cols, rows, col, row);
                Block spot = base.getRelative(lt.dx(), lt.dy(), lt.dz());
                ItemFrame fr = spawnFrame(w, spot, face, lt.rotation(), o.glow, o.invisible, o.fixed, mapItem(view), pid);
                frames.add(new Store.Frame(fr.getUniqueId(), spot.getX(), spot.getY(), spot.getZ(), view.getId(), face.name(), lt.rotation()));
                placed.add(new int[]{view.getId(), row * cols + col});
                centers.add(spot.getLocation().add(0.5, 0.5, 0.5).add(face.getModX() * 0.4, face.getModY() * 0.4, face.getModZ() * 0.4));
            }
        } catch (RuntimeException ex) {
            for (Store.Frame f : frames) { Entity en = Bukkit.getEntity(f.uuid()); if (en != null) en.remove(); }
            throw ex;
        }
        Store.Paste paste = new Store.Paste(pid, p.getUniqueId(), p.getName(), w.getName(), System.currentTimeMillis(), cols, rows, frames,
                o.invisible, o.glow, o.fixed);
        store.add(paste);
        byte[][] tiles = r.tiles();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            for (int[] pl : placed) store.saveTile(pl[0], tiles[pl[1]]);
        });
        lang.send(p, "placed", "cols", cols, "rows", rows, "count", frames.size(), "id", pid);
        if (bad > 0) lang.send(p, "skipped", "count", bad);
        placeEffect(w, face, centers);
    }

    // ---------- 設置演出 (確定した瞬間) ----------
    private static Map<String, Object> mp(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static double num(Map<?, ?> m, String k, double d) { return m.get(k) instanceof Number n ? n.doubleValue() : d; }

    private static String str(Map<?, ?> m, String k, String d) { return m.get(k) == null ? d : m.get(k).toString(); }

    private List<Map<?, ?>> mapList(ConfigurationSection s, String key, List<Map<String, Object>> def) {
        List<Map<?, ?>> l = s.getMapList(key);
        return l.isEmpty() && !s.isSet(key) ? new ArrayList<>(def) : l;
    }

    /**
     * 貼り付け範囲の面に沿って粒子を散らす。外周から中心へ波のように広がり、四隅でバーストする。
     * 数tickかけて段階的に出し、効果音も時間差で鳴らす。すべて effects.place で調整できる。
     */
    void placeEffect(World w, BlockFace face, List<Location> centers) {
        ConfigurationSection s = getConfig().getConfigurationSection("effects.place");
        if (s == null) s = new org.bukkit.configuration.MemoryConfiguration();
        if (!s.getBoolean("enabled", true) || centers.isEmpty()) return;
        final int dur = Math.max(1, s.getInt("duration-ticks", 24));
        final int cap = Math.max(1, s.getInt("max-tiles", 96));
        final double mult = Math.max(0, s.getDouble("count-multiplier", 1.0));
        final double spread = s.getDouble("spread", 1.0);
        final int[] n = {face.getModX(), face.getModY(), face.getModZ()};

        double cx = 0, cy = 0, cz = 0;
        double[] lo = {1e18, 1e18, 1e18}, hi = {-1e18, -1e18, -1e18};
        for (Location l : centers) {
            cx += l.getX(); cy += l.getY(); cz += l.getZ();
            double[] v = {l.getX(), l.getY(), l.getZ()};
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], v[i]); hi[i] = Math.max(hi[i], v[i]); }
        }
        final Location mid = new Location(w, cx / centers.size(), cy / centers.size(), cz / centers.size());
        double maxD = 1e-6;
        for (Location l : centers) maxD = Math.max(maxD, l.distance(mid));

        // 外周ほど早く、中心ほど遅く (外から中心へ収束する波)
        final int stride = Math.max(1, (centers.size() + cap - 1) / cap);
        final List<List<Location>> buckets = new ArrayList<>();
        for (int i = 0; i < dur; i++) buckets.add(new ArrayList<>());
        for (int i = 0; i < centers.size(); i += stride) {
            Location l = centers.get(i);
            int t = centers.size() == 1 ? 0 : (int) Math.round((1 - l.distance(mid) / maxD) * (dur - 1));
            buckets.get(Math.max(0, Math.min(dur - 1, t))).add(l);
        }

        record Layer(Particle p, int count, double speed) {}
        final List<Layer> layers = new ArrayList<>();
        for (Map<?, ?> m : mapList(s, "layers", List.of(
                mp("particle", "END_ROD", "count", 6, "speed", 0.04),
                mp("particle", "FIREWORK", "count", 4, "speed", 0.05),
                mp("particle", "GLOW", "count", 3, "speed", 0.02),
                mp("particle", "ENCHANT", "count", 8, "speed", 0.6),
                mp("particle", "TOTEM_OF_UNDYING", "count", 2, "speed", 0.15)))) {
            Particle p = particleOf(str(m, "particle", ""), null);
            if (p != null && p.getDataType() == Void.class) layers.add(new Layer(p, (int) num(m, "count", 3), num(m, "speed", 0.05)));
        }
        final List<Layer> corners = new ArrayList<>();
        for (Map<?, ?> m : mapList(s, "corner-bursts", List.of(
                mp("particle", "TOTEM_OF_UNDYING", "count", 30, "speed", 0.4),
                mp("particle", "END_ROD", "count", 24, "speed", 0.25),
                mp("particle", "FIREWORK", "count", 16, "speed", 0.2)))) {
            Particle p = particleOf(str(m, "particle", ""), null);
            if (p != null && p.getDataType() == Void.class) corners.add(new Layer(p, (int) num(m, "count", 10), num(m, "speed", 0.2)));
        }
        final Particle flash = particleOf(s.getString("flash-particle", "EXPLOSION_EMITTER"), null);
        final int flashCount = s.getInt("flash-count", 1);
        final List<Map<?, ?>> sounds = mapList(s, "sounds", List.of(
                mp("sound", "minecraft:entity.firework_rocket.launch", "volume", 1.0, "pitch", 1.0, "delay", 0),
                mp("sound", "minecraft:block.beacon.activate", "volume", 0.8, "pitch", 1.8, "delay", 0),
                mp("sound", "minecraft:entity.item_frame.place", "volume", 1.0, "pitch", 1.0, "delay", 0),
                mp("sound", "minecraft:entity.firework_rocket.blast", "volume", 1.0, "pitch", 1.0, "delay", 8),
                mp("sound", "minecraft:entity.firework_rocket.twinkle", "volume", 1.0, "pitch", 1.0, "delay", 12),
                mp("sound", "minecraft:entity.player.levelup", "volume", 0.9, "pitch", 1.2, "delay", dur - 1)));

        // 四隅 (面に沿った2軸の最小・最大)
        final List<Location> cornerPts = new ArrayList<>();
        int a1 = -1, a2 = -1;
        for (int i = 0; i < 3; i++) if (n[i] == 0) { if (a1 < 0) a1 = i; else a2 = i; }
        if (a1 >= 0 && a2 >= 0) for (int u = 0; u < 2; u++) for (int v = 0; v < 2; v++) {
            double[] pt = {mid.getX(), mid.getY(), mid.getZ()};
            pt[a1] = u == 0 ? lo[a1] - 0.5 : hi[a1] + 0.5;
            pt[a2] = v == 0 ? lo[a2] - 0.5 : hi[a2] + 0.5;
            cornerPts.add(new Location(w, pt[0], pt[1], pt[2]));
        }
        final double[] dd = {n[0] != 0 ? 0.12 : 0.5 * spread, n[1] != 0 ? 0.12 : 0.5 * spread, n[2] != 0 ? 0.12 : 0.5 * spread};
        final Location soundAt = mid;

        final int[] tick = {0};
        getServer().getScheduler().runTaskTimer(this, task -> {
            int t = tick[0]++;
            if (t == 0) {
                if (flash != null && flash.getDataType() == Void.class && flashCount > 0) w.spawnParticle(flash, mid, flashCount, 0, 0, 0, 0);
                for (Location cp : cornerPts) for (Layer ly : corners)
                    w.spawnParticle(ly.p(), cp, (int) Math.round(ly.count() * mult), 0.15, 0.15, 0.15, ly.speed());
            }
            if (t < dur) for (Location l : buckets.get(t)) {
                Location at = l.clone().subtract(n[0] * 0.3, n[1] * 0.3, n[2] * 0.3);
                for (Layer ly : layers) {
                    int c = (int) Math.round(ly.count() * mult);
                    if (c > 0) w.spawnParticle(ly.p(), at, c, dd[0], dd[1], dd[2], ly.speed());
                }
            }
            for (Map<?, ?> sm : sounds) {
                if ((int) num(sm, "delay", 0) != t) continue;
                String name = str(sm, "sound", "");
                if (!name.isBlank()) w.playSound(soundAt, name, SoundCategory.BLOCKS, (float) num(sm, "volume", 1), (float) num(sm, "pitch", 1));
            }
            if (t == dur - 1) {
                // 最後に四隅をもう一度弾けさせる
                for (Location cp : cornerPts) for (Layer ly : corners)
                    w.spawnParticle(ly.p(), cp, (int) Math.round(ly.count() * mult / 2.0), 0.2, 0.2, 0.2, ly.speed());
            }
            if (t >= dur) task.cancel();
        }, 0L, 1L);
    }

    /** 貼り付け・撤去の演出 (パーティクルと効果音)。config の effects.<名前> で変更できる */
    void effect(String name, World w, List<Location> centers) {
        ConfigurationSection s = getConfig().getConfigurationSection("effects." + name);
        if (s == null) { s = new org.bukkit.configuration.MemoryConfiguration(); s.set("particle", "HAPPY_VILLAGER"); s.set("count-per-tile", 8); s.set("sound", name.equals("place") ? "minecraft:entity.player.levelup" : "minecraft:entity.item_frame.remove_item"); }
        if (!s.getBoolean("enabled", true) || centers.isEmpty()) return;
        int cap = Math.max(1, s.getInt("max-tiles", 64));
        int stride = Math.max(1, (centers.size() + cap - 1) / cap);
        double spread = s.getDouble("spread", 0.6);
        for (String suffix : new String[]{"", "2"}) {
            Particle pt = particleOf(s.getString("particle" + suffix, suffix.isEmpty() ? "HAPPY_VILLAGER" : ""), null);
            int count = Math.max(0, s.getInt("count-per-tile" + suffix, suffix.isEmpty() ? 8 : 0));
            double speed = s.getDouble("speed" + suffix, 0.05);
            if (pt == null || count == 0 || pt.getDataType() != Void.class) continue;
            for (int i = 0; i < centers.size(); i += stride) w.spawnParticle(pt, centers.get(i), count, spread, spread, spread, speed);
        }
        double x = 0, y = 0, z = 0;
        for (Location l : centers) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        Location c = new Location(w, x / centers.size(), y / centers.size(), z / centers.size());
        for (String suffix : new String[]{"", "2"}) {
            String sound = s.getString("sound" + suffix, "");
            if (sound == null || sound.isBlank()) continue;
            w.playSound(c, sound, SoundCategory.BLOCKS, (float) s.getDouble("volume" + suffix, 1.0), (float) s.getDouble("pitch" + suffix, 1.0));
        }
    }

    private boolean canHang(Block spot, Block back, BlockFace face) {
        if (!back.getType().isSolid()) return false;
        Material m = spot.getType();
        if (!(m.isAir() || spot.isPassable()) || spot.isLiquid()) return false;
        if (!spot.getWorld().getNearbyEntities(spot.getBoundingBox().expand(-0.01), x -> x instanceof Hanging).isEmpty()) return false;
        return true;
    }


    // ---------- 削除・破損の整合 ----------
    /** 貼り付け全体を撤去する (額縁・記録・地図データ)。ドロップは出さない */
    void removePaste(Store.Paste p) {
        World w = Bukkit.getWorld(p.world);
        List<Location> centers = new ArrayList<>();
        if (w != null) {
            for (Store.Frame f : p.frames) {
                Entity en = Bukkit.getEntity(f.uuid());
                if (en == null) {
                    w.getChunkAt(f.x() >> 4, f.z() >> 4); // チャンクを読み込んで探す
                    en = Bukkit.getEntity(f.uuid());
                }
                if (en instanceof ItemFrame fr) { centers.add(fr.getLocation()); fr.setFixed(false); fr.setItem(null, false); fr.remove(); }
            }
        }
        store.remove(p);
        if (w != null) effect("remove", w, centers);
    }

    /** 額縁が (保護をすり抜けて) 失われた。次の tick に、設定に従って記録と残りのタイルを整える */
    void frameLost(UUID frameId) {
        getServer().getScheduler().runTask(this, () -> handleLost(frameId));
    }

    private void handleLost(UUID id) {
        Store.Paste p = store.byFrame(id);
        if (p == null) return;
        Store.Frame f = null;
        for (Store.Frame x : p.frames) if (x.uuid().equals(id)) { f = x; break; }
        if (f == null) return;
        switch (onBreak) {
            case REMOVE_ALL -> { removePaste(p); notifyOwner(p, "broken-removed", "id", p.id); }
            case REGENERATE -> {
                Store.Frame nf = null;
                try { nf = respawn(p, f); } catch (RuntimeException ex) { getLogger().warning("額縁の再生成に失敗: " + ex); }
                if (nf != null) notifyOwner(p, "broken-regenerated", "id", p.id);
                else dropFrame(p, f);
            }
            default -> dropFrame(p, f);
        }
    }

    private void dropFrame(Store.Paste p, Store.Frame f) {
        boolean empty = store.dropFrame(p, f);
        if (empty) notifyOwner(p, "broken-gone", "id", p.id);
        else notifyOwner(p, "broken-partial", "id", p.id, "count", p.frames.size());
    }

    /** 同じ場所・向き・地図で額縁を作り直す。置けなければ null */
    private Store.Frame respawn(Store.Paste p, Store.Frame f) {
        World w = Bukkit.getWorld(p.world);
        if (w == null || f.face() == null) return null;
        BlockFace face = BlockFace.valueOf(f.face());
        MapView view = Bukkit.getMap(f.mapId());
        if (view == null) return null;
        Block spot = w.getBlockAt(f.x(), f.y(), f.z());
        if (!canHang(spot, spot.getRelative(face.getOppositeFace()), face)) return null;
        ItemFrame fr = spawnFrame(w, spot, face, f.rot(), p.glow, p.invisible, p.fixed, mapItem(view), p.id);
        Store.Frame nf = f.withUuid(fr.getUniqueId());
        store.replaceFrame(p, f, nf);
        return nf;
    }

    /** 同じ貼り付けの通知は2秒に1回まで (一括で壊れたときの連投を防ぐ) */
    private void notifyOwner(Store.Paste p, String key, Object... kv) {
        getLogger().info("貼り付け " + p.id + ": " + key);
        long now = System.currentTimeMillis();
        Long last = notified.get(p.id + key);
        if (last != null && now - last < 2000) return;
        notified.put(p.id + key, now);
        Player o = getServer().getPlayer(p.owner);
        if (o != null) lang.send(o, key, kv);
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
        endSession(e.getPlayer().getUniqueId());
        lastUse.remove(e.getPlayer().getUniqueId());
    }
}
