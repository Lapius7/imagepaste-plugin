package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
    private static final MiniMessage MM = MiniMessage.miniMessage();
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

    private static TagResolver resolver(Object... kv) {
        List<TagResolver> l = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String key = String.valueOf(kv[i]);
            // click:run_command:'...<id>' の引用符内では <id> が展開されないため、安全な ID だけは先に文字列として埋め込む
            if (key.equals("id") && v != null && String.valueOf(v).matches("[A-Za-z0-9_-]{1,32}")) { l.add(Placeholder.parsed(key, String.valueOf(v))); continue; }
            l.add(v instanceof Component c ? Placeholder.component(String.valueOf(kv[i]), c)
                    : Placeholder.unparsed(String.valueOf(kv[i]), String.valueOf(v)));
        }
        return TagResolver.resolver(l);
    }

    /** 接頭辞なしの文言 */
    public Component c(String key, Object... kv) { return MM.deserialize(raw(key), resolver(kv)); }

    public Component prefixed(String key, Object... kv) { return MM.deserialize(raw("prefix") + raw(key), resolver(kv)); }

    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(prefixed(key, kv)); }

    /** テンプレート文字列をそのまま描画する (テスト用にも使える) */
    public static Component render(String mini, Object... kv) { return MM.deserialize(mini, resolver(kv)); }

    public Component parse(String mini, Object... kv) { return MM.deserialize(mini, resolver(kv)); }
}
