package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LangTest {
    private static void clicks(Component c, List<String> out) {
        if (c.clickEvent() != null && c.clickEvent().payload() instanceof ClickEvent.Payload.Text t) out.add(t.value());
        c.children().forEach(ch -> clicks(ch, out));
    }

    @Test
    void placedRemoveButtonUsesRealId() {
        var yml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                LangTest.class.getResourceAsStream("/lang.yml"), StandardCharsets.UTF_8));
        Component c = Lang.render(yml.getString("placed"), "cols", 2, "rows", 2, "count", 4, "id", "km2xq");
        List<String> l = new ArrayList<>();
        clicks(c, l);
        assertEquals(List.of("/imagepaste remove km2xq"), l);
    }

    @Test
    void everyClickTemplateExpandsPlaceholders() {
        var yml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                LangTest.class.getResourceAsStream("/lang.yml"), StandardCharsets.UTF_8));
        for (String key : yml.getKeys(false)) {
            String v = yml.getString(key);
            if (v == null || !v.contains("<click:")) continue;
            List<String> l = new ArrayList<>();
            clicks(Lang.render(v, "id", "abc12", "arg", "x", "cols", 1, "rows", 1, "count", 1), l);
            assertFalse(l.isEmpty(), key);
            l.forEach(x -> assertFalse(x.contains("<"), key + ": " + x));
        }
    }
}
