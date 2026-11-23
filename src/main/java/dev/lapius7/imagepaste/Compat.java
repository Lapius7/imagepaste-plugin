package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Particle;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 版による API の差を吸収する。コンパイルは最小対応版 (1.18.2) の API に対して行うので、
 * それより新しい API と、版で名前が変わったものはここで実行時に解決する。
 */
final class Compat {
    private Compat() {}

    // ---------- パーティクル名 (1.20.5 で改名されたもの。新旧どちらの名前でも指定できる) ----------
    private static final Map<String, String> ALIAS = new HashMap<>();

    static {
        String[][] pairs = {
                {"DUST", "REDSTONE"}, {"EXPLOSION_EMITTER", "EXPLOSION_HUGE"}, {"EXPLOSION", "EXPLOSION_LARGE"},
                {"POOF", "EXPLOSION_NORMAL"}, {"HAPPY_VILLAGER", "VILLAGER_HAPPY"}, {"ANGRY_VILLAGER", "VILLAGER_ANGRY"},
                {"FIREWORK", "FIREWORKS_SPARK"}, {"ENTITY_EFFECT", "SPELL_MOB"}, {"EFFECT", "SPELL"},
                {"INSTANT_EFFECT", "SPELL_INSTANT"}, {"WITCH", "SPELL_WITCH"}, {"CRIT", "CRIT"},
                {"ENCHANTED_HIT", "CRIT_MAGIC"}, {"SMALL_FLAME", "FLAME"}, {"DRIPPING_WATER", "DRIP_WATER"},
                {"DRIPPING_LAVA", "DRIP_LAVA"}, {"BUBBLE", "WATER_BUBBLE"}, {"SPLASH", "WATER_SPLASH"},
                {"FISHING", "WATER_WAKE"}, {"UNDERWATER", "SUSPENDED"}, {"SNEEZE", "SNEEZE"},
                {"ITEM_SNOWBALL", "SNOWBALL"}, {"ITEM_SLIME", "SLIME"}, {"LARGE_SMOKE", "SMOKE_LARGE"},
                {"SMOKE", "SMOKE_NORMAL"}, {"ENCHANT", "ENCHANTMENT_TABLE"}, {"TOTEM_OF_UNDYING", "TOTEM"}, {"CAMPFIRE_COSY_SMOKE", "CAMPFIRE_COSY_SMOKE"}, {"BLOCK_MARKER", "BLOCK_MARKER"}, {"TRIAL_SPAWNER_DETECTION", "TRIAL_SPAWNER_DETECTION"},
        };
        for (String[] p : pairs) {
            ALIAS.putIfAbsent(p[0], p[1]);
            ALIAS.putIfAbsent(p[1], p[0]);
        }
    }

    /** 名前からパーティクルを探す。新旧の別名も試し、なければ def */
    static Particle particle(String name, Particle def) {
        if (name == null || name.isBlank()) return def;
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.startsWith("MINECRAFT:")) n = n.substring(10);
        for (String cand : new String[]{n, ALIAS.get(n)}) {
            if (cand == null) continue;
            try { return Particle.valueOf(cand); } catch (IllegalArgumentException ignored) {}
        }
        return def;
    }

    /** 色つきの粒子。1.20.5 以降は DUST、それ以前は REDSTONE */
    static Particle dust() { return particle("DUST", null); }

    // ---------- スコアボード (サイドバー表示) ----------
    private static Method method(Class<?> c, String name, Class<?>... args) {
        try { return c.getMethod(name, args); } catch (NoSuchMethodException e) { return null; }
    }

    /** 目的を作り、サイドバーに出す。得点の数字は隠せる版では隠す */
    static Objective sidebar(Scoreboard sb, String name, Component title) {
        Objective ob = null;
        try {
            Class<?> crit = Class.forName("org.bukkit.scoreboard.Criteria");
            Object dummy = crit.getField("DUMMY").get(null);
            Method m = Scoreboard.class.getMethod("registerNewObjective", String.class, crit, Component.class);
            ob = (Objective) m.invoke(sb, name, dummy, title);
        } catch (ReflectiveOperationException ignored) {
        }
        if (ob == null) ob = sb.registerNewObjective(name, "dummy", title);
        ob.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            Method m = method(Objective.class, "numberFormat", nf);
            if (m != null) m.invoke(ob, blank);
        } catch (ReflectiveOperationException ignored) {
        }
        return ob;
    }

    private static final Method CUSTOM_NAME = method(Score.class, "customName", Component.class);

    /** 1行を足す。customName が使える版 (1.20.4+) は見た目と記録名を分け、古い版は文字列そのものを項目名にする */
    static void sidebarLine(Objective ob, int index, int score, Component text) {
        if (CUSTOM_NAME != null) {
            Score sc = ob.getScore("§" + Integer.toHexString(index));
            sc.setScore(score);
            try { CUSTOM_NAME.invoke(sc, text); } catch (ReflectiveOperationException ignored) {}
            return;
        }
        String s = LegacyComponentSerializer.legacySection().serialize(text);
        if (s.length() > 38) s = s.substring(0, 38);
        // 同じ文字列の行が重ならないよう、見えない色コードを末尾に付ける
        s += "§" + Integer.toHexString(index % 16) + "§r";
        ob.getScore(s).setScore(score);
    }
}
