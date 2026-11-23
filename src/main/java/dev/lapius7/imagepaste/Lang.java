package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** lang.yml の文言 (MiniMessage)。足りない項目は内蔵の既定文で補う。 */
public final class Lang {
    private final JavaPlugin plugin;
    private YamlConfiguration yml;

    public Lang(JavaPlugin plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "lang.yml");
        if (!f.exists()) plugin.saveResource("lang.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("lang.yml");
        if (in != null) yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public String raw(String key) { return yml.getString(key, key); }

    public List<String> list(String key) { return yml.getStringList(key); }

    /** 文言の描画器。MiniMessage があればそれを、無い版 (Paper 1.16.5) では MiniSimple を使う */
    interface Renderer { Component render(String mini, Object... kv); }

    private static final Renderer R = pick();

    private static Renderer pick() {
        try {
            Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            return new MiniNative();
        } catch (ReflectiveOperationException | LinkageError e) {
            return new MiniSimple();
        }
    }

    /** 接頭辞なしの文言 */
    public Component c(String key, Object... kv) { return R.render(raw(key), kv); }

    public Component prefixed(String key, Object... kv) { return R.render(raw("prefix") + raw(key), kv); }

    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(prefixed(key, kv)); }

    /** テンプレート文字列をそのまま描画する (テスト用にも使える) */
    public static Component render(String mini, Object... kv) { return R.render(mini, kv); }

    public Component parse(String mini, Object... kv) { return R.render(mini, kv); }

    /** id だけは安全な文字なら先に文字列として埋め込む (click:run_command:'...<id>' の引用符内では <id> が展開されないため) */
    static String inlineId(String mini, Object[] kv) {
        for (int i = 0; i + 1 < kv.length; i += 2)
            if ("id".equals(String.valueOf(kv[i])) && kv[i + 1] != null && String.valueOf(kv[i + 1]).matches("[A-Za-z0-9_-]{1,32}"))
                return mini.replace("<id>", String.valueOf(kv[i + 1]));
        return mini;
    }
}
