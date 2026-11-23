package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/** /imagepaste (別名 /ip) の処理と tab 補完 */
final class Commands implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("help", "list", "undo", "remove", "tp", "size", "rotate", "flip", "confirm", "cancel", "reload");
    private final ImagePastePlugin pl;
    private final Lang lang;

    Commands(ImagePastePlugin pl) { this.pl = pl; this.lang = pl.lang; }

    private boolean admin(CommandSender s) { return s.hasPermission("imagepaste.admin"); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("imagepaste.use")) { lang.send(s, "no-permission"); return true; }
        if (a.length == 0) { help(s); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(s);
            case "list" -> list(s, a);
            case "undo" -> undo(s);
            case "remove" -> remove(s, a);
            case "tp" -> tp(s, a);
            case "size" -> size(s, a);
            case "rotate" -> rotate(s, a);
            case "flip" -> flip(s, a);
            case "confirm" -> { if (s instanceof Player p) { var se = pl.sessions.get(p.getUniqueId()); if (se == null) lang.send(s, "nothing-pending"); else pl.confirm(p, se); } else lang.send(s, "players-only"); }
            case "cancel" -> {
                if (!(s instanceof Player p)) { lang.send(s, "players-only"); return true; }
                if (pl.sessions.containsKey(p.getUniqueId())) { pl.endSession(p.getUniqueId()); lang.send(s, "cancelled"); }
                else lang.send(s, "nothing-pending");
            }
            case "reload" -> {
                if (!admin(s)) { lang.send(s, "no-permission"); return true; }
                pl.reloadAll();
                lang.send(s, "reloaded");
            }
            default -> {
                if (!a[0].contains("://")) { lang.send(s, "unknown-sub", "arg", a[0]); return true; }
                if (!(s instanceof Player p)) { lang.send(s, "players-only"); return true; }
                pl.startPaste(p, a);
            }
        }
        return true;
    }

    private Component line(String cmd, String usage, String descKey) {
        return Component.text(" ").append(lang.parse("<aqua>/ip " + usage + "</aqua> <dark_gray>-</dark_gray> <gray><d></gray>", "d", lang.raw(descKey)))
                .clickEvent(ClickEvent.suggestCommand("/imagepaste " + cmd))
                .hoverEvent(HoverEvent.showText(lang.parse("<gray>クリックで入力: <white>/imagepaste " + cmd)));
    }

    private void help(CommandSender s) {
        s.sendMessage(lang.c("help-header", "version", pl.getPluginMeta().getVersion()));
        s.sendMessage(lang.c("help-hint"));
        s.sendMessage(lang.c("help-section-paste"));
        s.sendMessage(line("https://", "<URL> [オプション]", "cmd-url"));
        s.sendMessage(lang.c("help-options"));
        s.sendMessage(lang.c("help-section-preview"));
        s.sendMessage(line("size ", "size <幅> [高さ]", "cmd-size"));
        s.sendMessage(line("rotate ", "rotate [角度]", "cmd-rotate"));
        s.sendMessage(line("flip ", "flip [h|v|both|none]", "cmd-flip"));
        s.sendMessage(line("confirm", "confirm", "cmd-confirm"));
        s.sendMessage(line("cancel", "cancel", "cmd-cancel"));
        s.sendMessage(lang.c("help-controls"));
        s.sendMessage(lang.c("help-section-manage"));
        s.sendMessage(line("list", "list [all] [ページ]", "cmd-list"));
        s.sendMessage(line("undo", "undo", "cmd-undo"));
        s.sendMessage(line("remove ", "remove <ID>", "cmd-remove"));
        s.sendMessage(line("tp ", "tp <ID>", "cmd-tp"));
        if (admin(s)) s.sendMessage(line("reload", "reload", "cmd-reload"));
        s.sendMessage(lang.c("help-footer"));
    }

    private void list(CommandSender s, String[] a) {
        boolean all = false;
        int page = 1;
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("all")) all = true;
            else try { page = Math.max(1, Integer.parseInt(a[i])); } catch (NumberFormatException ignored) {}
        }
        List<Store.Paste> l;
        if (all && admin(s)) l = pl.store.all();
        else if (s instanceof Player p) { l = pl.store.byOwner(p.getUniqueId()); all = false; }
        else l = pl.store.all();
        if (l.isEmpty()) { lang.send(s, "list-empty"); return; }
        int size = Math.max(1, pl.getConfig().getInt("list.page-size", 8));
        int pages = (l.size() + size - 1) / size;
        page = Math.min(page, pages);
        s.sendMessage(lang.c("list-header", "page", page, "pages", pages, "total", l.size()));
        for (int i = (page - 1) * size; i < Math.min(l.size(), page * size); i++) {
            Store.Paste p = l.get(i);
            Store.Frame f = p.frames.isEmpty() ? null : p.frames.get(0);
            Component row = lang.c("list-line", "id", p.id, "cols", p.cols, "rows", p.rows, "world", p.world,
                    "x", f == null ? 0 : f.x(), "y", f == null ? 0 : f.y(), "z", f == null ? 0 : f.z(), "owner", p.ownerName);
            boolean mine = s instanceof Player pp && pp.getUniqueId().equals(p.owner);
            if (mine || admin(s)) row = row.append(Component.text(" ")).append(lang.c("btn-remove")
                    .clickEvent(ClickEvent.runCommand("/imagepaste remove " + p.id)).hoverEvent(HoverEvent.showText(lang.c("btn-remove-hover"))));
            if (s instanceof Player && (mine || admin(s))) row = row.append(Component.text(" ")).append(lang.c("btn-tp")
                    .clickEvent(ClickEvent.runCommand("/imagepaste tp " + p.id)).hoverEvent(HoverEvent.showText(lang.c("btn-tp-hover"))));
            s.sendMessage(row);
        }
        String base = "/imagepaste list " + (all ? "all " : "");
        Component nav = Component.empty();
        if (page > 1) nav = nav.append(lang.c("btn-prev").clickEvent(ClickEvent.runCommand(base + (page - 1))));
        if (page > 1 && page < pages) nav = nav.append(Component.text("  "));
        if (page < pages) nav = nav.append(lang.c("btn-next").clickEvent(ClickEvent.runCommand(base + (page + 1))));
        if (pages > 1) s.sendMessage(nav);
        s.sendMessage(lang.c("list-footer"));
    }

    private void undo(CommandSender s) {
        if (!(s instanceof Player p)) { lang.send(s, "players-only"); return; }
        Store.Paste last = pl.store.lastOf(p.getUniqueId());
        if (last == null) { lang.send(s, "nothing-to-undo"); return; }
        pl.removePaste(last);
        lang.send(s, "undone");
    }

    private void remove(CommandSender s, String[] a) {
        if (a.length < 2) { lang.send(s, "bad-option", "opt", "remove <ID>"); return; }
        Store.Paste p = pl.store.find(a[1]);
        if (p == null) { lang.send(s, "not-found"); return; }
        boolean mine = s instanceof Player pp && pp.getUniqueId().equals(p.owner);
        if (!mine && !admin(s)) { lang.send(s, "no-permission"); return; }
        pl.removePaste(p);
        lang.send(s, "removed", "id", p.id);
    }

    private void tp(CommandSender s, String[] a) {
        if (!(s instanceof Player pp)) { lang.send(s, "players-only"); return; }
        if (a.length < 2) { lang.send(s, "bad-option", "opt", "tp <ID>"); return; }
        Store.Paste p = pl.store.find(a[1]);
        if (p == null) { lang.send(s, "not-found"); return; }
        if (!pp.getUniqueId().equals(p.owner) && !admin(s)) { lang.send(s, "no-permission"); return; }
        World w = org.bukkit.Bukkit.getWorld(p.world);
        if (w == null || p.frames.isEmpty()) { lang.send(s, "tp-failed"); return; }
        Store.Frame f = p.frames.get(0);
        Location to = new Location(w, f.x() + 0.5, f.y(), f.z() + 0.5, pp.getLocation().getYaw(), pp.getLocation().getPitch());
        if (f.face() != null) {
            org.bukkit.block.BlockFace bf = org.bukkit.block.BlockFace.valueOf(f.face());
            to.add(bf.getModX() * 2, bf.getModY() * 2, bf.getModZ() * 2);
        }
        pp.teleportAsync(to);
        lang.send(s, "tp-done", "id", p.id);
    }

    private void size(CommandSender s, String[] a) {
        pl.withSession(s, (p, se) -> {
            try {
                if (a.length < 2) { lang.send(p, "bad-option", "opt", "size <幅> [高さ]"); return; }
                int w = Integer.parseInt(a[1]), h = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                pl.applySize(p, se, w, h);
            } catch (NumberFormatException e) { lang.send(p, "bad-option", "opt", "数値で指定してください"); }
        });
    }

    private void rotate(CommandSender s, String[] a) {
        pl.withSession(s, (p, se) -> {
            if (a.length < 2) { se.o.rotate = (se.o.rotate + 90) % 360; return; }
            String err = Opts.set(se.o, "rotate", a[1]);
            if (err != null) lang.send(p, "bad-option", "opt", err);
        });
    }

    private void flip(CommandSender s, String[] a) {
        pl.withSession(s, (p, se) -> {
            if (a.length < 2) { se.o.flip = (se.o.flip + 1) % 4; return; }
            String err = Opts.set(se.o, "flip", a[1]);
            if (err != null) lang.send(p, "bad-option", "opt", err);
        });
    }

    private static List<String> match(Collection<String> src, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> r = new ArrayList<>();
        for (String x : src) if (x.toLowerCase(Locale.ROOT).startsWith(p)) r.add(x);
        return r;
    }

    private List<String> ids(CommandSender s) {
        List<String> r = new ArrayList<>();
        List<Store.Paste> l = admin(s) || !(s instanceof Player p) ? pl.store.all() : pl.store.byOwner(p.getUniqueId());
        for (Store.Paste p : l) r.add(p.id);
        return r;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("imagepaste.use")) return List.of();
        if (a.length == 1) {
            List<String> l = new ArrayList<>(SUBS);
            if (!admin(s)) l.remove("reload");
            l.add("https://");
            return match(l, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String cur = a[a.length - 1];
        switch (sub) {
            case "remove", "tp" -> { return a.length == 2 ? match(ids(s), cur) : List.of(); }
            case "list" -> {
                List<String> l = new ArrayList<>();
                if (admin(s)) l.add("all");
                int n = Math.max(1, (pl.store.all().size() + 7) / 8);
                for (int i = 1; i <= Math.min(n, 9); i++) l.add(String.valueOf(i));
                return match(l, cur);
            }
            case "size" -> { return a.length <= 3 ? match(List.of("2", "4", "6", "8", "12", "16"), cur) : List.of(); }
            case "rotate" -> { return a.length == 2 ? match(List.of("0", "90", "180", "270"), cur) : List.of(); }
            case "flip" -> { return a.length == 2 ? match(List.of("none", "h", "v", "both"), cur) : List.of(); }
            case "help", "undo", "confirm", "cancel", "reload" -> { return List.of(); }
            default -> {}
        }
        if (!a[0].contains("://")) return List.of();
        int eq = cur.indexOf('=');
        if (eq < 0) {
            List<String> keys = new ArrayList<>();
            for (String k : Opts.KEYS) keys.add(k + "=");
            return match(keys, cur);
        }
        String key = cur.substring(0, eq).toLowerCase(Locale.ROOT), val = cur.substring(eq + 1);
        List<String> vals = switch (key) {
            case "mode" -> List.of("fit", "fill", "stretch");
            case "quality" -> List.of("low", "normal", "high");
            case "resample" -> List.of("nearest", "bilinear", "bicubic");
            case "dither" -> List.of("none", "floyd", "ordered");
            case "rotate" -> List.of("0", "90", "180", "270");
            case "flip" -> List.of("none", "h", "v", "both");
            case "invisible", "glow", "fixed" -> List.of("true", "false");
            case "blocked" -> List.of("abort", "skip");
            case "background" -> List.of("transparent", "#000000", "#ffffff");
            case "width", "height" -> List.of("2", "4", "6", "8", "12", "16");
            default -> List.of();
        };
        List<String> r = new ArrayList<>();
        for (String v : match(vals, val)) r.add(key + "=" + v);
        return r;
    }
}
