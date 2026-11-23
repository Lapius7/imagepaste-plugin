package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MiniMessage を持たない版 (Paper 1.16.5) 向けの、lang.yml で使う範囲だけの簡易描画。
 * 色 (名前 / #rrggbb)、bold / italic / underlined / strikethrough / obfuscated、reset、gradient、
 * click、hover:show_text、newline、<名前> の差し込みに対応する。知らないタグはそのまま文字として出す。
 */
final class MiniSimple implements Lang.Renderer {
    private static final class Frame {
        final String name;
        Style style;
        TextColor[] gradient; // gradient の始点と終点
        int segStart;          // gradient が始まった時点の部品数
        Frame(String name, Style style) { this.name = name; this.style = style; }
    }

    @Override
    public Component render(String mini, Object... kv) {
        return parse(Lang.inlineId(mini, kv), kv);
    }

    private static Component parse(String s, Object[] kv) {
        List<Component> out = new ArrayList<>();
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame("", Style.empty()));
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '<') { text.append('<'); i += 2; continue; }
            if (ch != '<') { text.append(ch); i++; continue; }
            int end = tagEnd(s, i);
            if (end < 0) { text.append(ch); i++; continue; }
            String tag = s.substring(i + 1, end);
            if (!handle(tag, stack, out, text, kv)) { text.append(ch); i++; continue; }
            i = end + 1;
        }
        flush(out, text, stack);
        while (stack.size() > 1) close(stack, out);
        return Component.empty().children(out);
    }

    /** '<' から対応する '>' を探す (引用符の中の '>' は飛ばす) */
    private static int tagEnd(String s, int from) {
        char q = 0;
        for (int j = from + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == '>') return j;
            if (c == '<') return -1;
        }
        return -1;
    }

    private static Style cur(List<Frame> st) { return st.get(st.size() - 1).style; }

    private static void flush(List<Component> out, StringBuilder text, List<Frame> st) {
        if (text.length() == 0) return;
        out.add(Component.text(text.toString(), cur(st)));
        text.setLength(0);
    }

    private static boolean handle(String tag, List<Frame> st, List<Component> out, StringBuilder text, Object[] kv) {
        if (tag.startsWith("/")) {
            String n = tag.substring(1).toLowerCase(Locale.ROOT);
            for (int k = st.size() - 1; k >= 1; k--) {
                if (st.get(k).name.equals(n)) {
                    flush(out, text, st);
                    while (st.size() > k) close(st, out);
                    return true;
                }
            }
            return false;
        }
        List<String> a = split(tag);
        String name = a.get(0).toLowerCase(Locale.ROOT);
        if (name.equals("newline") || name.equals("br")) { flush(out, text, st); out.add(Component.newline()); return true; }
        if (name.equals("reset")) { flush(out, text, st); st.add(new Frame("reset", Style.empty())); return true; }
        for (int k = 0; k + 1 < kv.length; k += 2) {
            if (!name.equals(String.valueOf(kv[k]))) continue;
            flush(out, text, st);
            Object v = kv[k + 1];
            out.add(v instanceof Component ? Component.text("", cur(st)).append((Component) v) : Component.text(String.valueOf(v), cur(st)));
            return true;
        }
        Style base = cur(st);
        Frame f = null;
        TextColor color = color(name);
        if (color != null) f = new Frame(name, base.color(color));
        else if (name.equals("bold") || name.equals("b")) f = new Frame(name, base.decoration(TextDecoration.BOLD, true));
        else if (name.equals("italic") || name.equals("i") || name.equals("em")) f = new Frame(name, base.decoration(TextDecoration.ITALIC, true));
        else if (name.equals("underlined") || name.equals("u")) f = new Frame(name, base.decoration(TextDecoration.UNDERLINED, true));
        else if (name.equals("strikethrough") || name.equals("st")) f = new Frame(name, base.decoration(TextDecoration.STRIKETHROUGH, true));
        else if (name.equals("obfuscated") || name.equals("obf")) f = new Frame(name, base.decoration(TextDecoration.OBFUSCATED, true));
        else if (name.equals("color") || name.equals("c")) {
            TextColor c = a.size() > 1 ? color(a.get(1).toLowerCase(Locale.ROOT)) : null;
            if (c == null) return false;
            f = new Frame(name, base.color(c));
        } else if (name.equals("click") && a.size() >= 3) {
            String act = a.get(1).toLowerCase(Locale.ROOT);
            String val = a.get(2);
            ClickEvent.Action action = act.equals("run_command") ? ClickEvent.Action.RUN_COMMAND
                    : act.equals("suggest_command") ? ClickEvent.Action.SUGGEST_COMMAND
                    : act.equals("open_url") ? ClickEvent.Action.OPEN_URL
                    : act.equals("copy_to_clipboard") ? ClickEvent.Action.COPY_TO_CLIPBOARD : null;
            if (action == null) return false;
            f = new Frame(name, base.clickEvent(ClickEvent.clickEvent(action, val)));
        } else if (name.equals("hover") && a.size() >= 3 && a.get(1).equalsIgnoreCase("show_text")) {
            f = new Frame(name, base.hoverEvent(HoverEvent.showText(parse(a.get(2), kv))));
        } else if (name.equals("gradient")) {
            TextColor c1 = a.size() > 1 ? color(a.get(1).toLowerCase(Locale.ROOT)) : null;
            TextColor c2 = a.size() > 2 ? color(a.get(2).toLowerCase(Locale.ROOT)) : null;
            if (c1 == null) { c1 = NamedTextColor.WHITE; }
            if (c2 == null) { c2 = c1; }
            f = new Frame(name, base);
            f.gradient = new TextColor[]{c1, c2};
        }
        if (f == null) return false;
        flush(out, text, st);
        f.segStart = out.size();
        st.add(f);
        return true;
    }

    /** タグの中身を ':' で区切る。引用符 ('...' / "...") の中の ':' では切らず、引用符は外す */
    private static List<String> split(String tag) {
        List<String> r = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        char q = 0;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (q != 0) { if (c == q) q = 0; else b.append(c); continue; }
            if (c == '\'' || c == '"') { q = c; continue; }
            if (c == ':') { r.add(b.toString()); b.setLength(0); continue; }
            b.append(c);
        }
        r.add(b.toString());
        return r;
    }

    private static TextColor color(String n) {
        if (n.startsWith("#")) return TextColor.fromCSSHexString(n);
        return NamedTextColor.NAMES.value(n);
    }

    /** 一番内側のタグを閉じる。gradient なら、それまでの文字に色を1文字ずつ振る */
    private static void close(List<Frame> st, List<Component> out) {
        Frame f = st.remove(st.size() - 1);
        if (f.gradient == null) return;
        List<Component> seg = new ArrayList<>(out.subList(f.segStart, out.size()));
        int total = 0;
        for (Component c : seg) if (c instanceof TextComponent) total += ((TextComponent) c).content().length();
        while (out.size() > f.segStart) out.remove(out.size() - 1);
        int n = 0;
        for (Component c : seg) {
            if (!(c instanceof TextComponent)) { out.add(c); continue; }
            TextComponent t = (TextComponent) c;
            for (char ch : t.content().toCharArray()) {
                double r = total <= 1 ? 0 : (double) n / (total - 1);
                out.add(Component.text(String.valueOf(ch), t.style().color(lerp(f.gradient[0], f.gradient[1], r))));
                n++;
            }
        }
    }

    private static TextColor lerp(TextColor a, TextColor b, double t) {
        int r = (int) Math.round(a.red() + (b.red() - a.red()) * t);
        int g = (int) Math.round(a.green() + (b.green() - a.green()) * t);
        int bl = (int) Math.round(a.blue() + (b.blue() - a.blue()) * t);
        return TextColor.color(r, g, bl);
    }
}
