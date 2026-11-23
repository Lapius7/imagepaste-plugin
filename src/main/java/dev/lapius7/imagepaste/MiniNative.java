package dev.lapius7.imagepaste;

import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 本物の MiniMessage による描画。MiniMessage を持つ版 (Paper 1.18 以降) だけで使う。
 * コンパイルは 1.16.5 の API に対して行うので、MiniMessage は反射で呼ぶ。
 */
final class MiniNative implements Lang.Renderer {
    private final Object mm;
    private final Method deserialize, component, unparsed, resolverOf;
    private final Class<?> resolverClass;

    MiniNative() throws ReflectiveOperationException {
        Class<?> mmc = Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
        resolverClass = Class.forName("net.kyori.adventure.text.minimessage.tag.resolver.TagResolver");
        Class<?> ph = Class.forName("net.kyori.adventure.text.minimessage.tag.resolver.Placeholder");
        mm = mmc.getMethod("miniMessage").invoke(null);
        deserialize = mmc.getMethod("deserialize", String.class, resolverClass);
        component = ph.getMethod("component", String.class, Class.forName("net.kyori.adventure.text.ComponentLike"));
        unparsed = ph.getMethod("unparsed", String.class, String.class);
        resolverOf = resolverClass.getMethod("resolver", Iterable.class);
    }

    @Override
    public Component render(String mini, Object... kv) {
        try {
            List<Object> l = new ArrayList<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object v = kv[i + 1];
                String key = String.valueOf(kv[i]);
                l.add(v instanceof Component ? component.invoke(null, key, v) : unparsed.invoke(null, key, String.valueOf(v)));
            }
            return (Component) deserialize.invoke(mm, Lang.inlineId(mini, kv), resolverOf.invoke(null, l));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
