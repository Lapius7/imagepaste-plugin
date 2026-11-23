package dev.lapius7.imagepaste;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/** 貼り付け1回分の設定。config.yml の defaults を元に、コマンド引数で上書きする。 */
public final class Opts {
    public static final List<String> KEYS = List.of("width", "height", "mode", "quality", "resample", "dither",
            "brightness", "contrast", "saturation", "background", "rotate", "flip", "invisible", "glow", "fixed", "blocked");

    public int width = 4, height = 0, rotate = 0;
    /** bit0 = 左右反転, bit1 = 上下反転 */
    public int flip = 0;
    public String mode = "fit", resample = "bicubic", dither = "floyd", blocked = "abort";
    public double brightness = 0, contrast = 1, saturation = 1;
    /** null = 透明 */
    public Integer background = null;
    public boolean invisible = true, glow = false, fixed = true;

    public static Opts fromConfig(FileConfiguration c) {
        Opts o = new Opts();
        String p = "defaults.";
        o.width = c.getInt(p + "width", 4);
        o.height = c.getInt(p + "height", 0);
        o.mode = c.getString(p + "mode", "fit").toLowerCase(Locale.ROOT);
        o.resample = c.getString(p + "resample", "bicubic").toLowerCase(Locale.ROOT);
        o.dither = c.getString(p + "dither", "floyd").toLowerCase(Locale.ROOT);
        o.brightness = c.getDouble(p + "brightness", 0);
        o.contrast = c.getDouble(p + "contrast", 1);
        o.saturation = c.getDouble(p + "saturation", 1);
        o.rotate = c.getInt(p + "rotate", 0);
        String fl = c.getString(p + "flip", "none");
        if (set(o, "flip", fl) != null) o.flip = 0;
        o.invisible = c.getBoolean(p + "invisible-frame", true);
        o.glow = c.getBoolean(p + "glow-frame", false);
        o.fixed = c.getBoolean(p + "fixed", true);
        o.blocked = c.getString(p + "on-blocked", "abort").toLowerCase(Locale.ROOT);
        String bg = c.getString(p + "background", "transparent");
        String err = set(o, "background", bg);
        if (err != null) o.background = null;
        // 不正な設定値は既定値に戻す
        if (!List.of("fit", "fill", "stretch").contains(o.mode)) o.mode = "fit";
        if (!List.of("nearest", "bilinear", "bicubic").contains(o.resample)) o.resample = "bicubic";
        if (!List.of("none", "floyd", "ordered").contains(o.dither)) o.dither = "floyd";
        if (!List.of("abort", "skip").contains(o.blocked)) o.blocked = "abort";
        if (o.rotate % 90 != 0) o.rotate = 0;
        o.rotate = ((o.rotate % 360) + 360) % 360;
        return o;
    }

    public Opts copy() {
        Opts o = new Opts();
        o.width = width; o.height = height; o.rotate = rotate; o.flip = flip; o.mode = mode; o.resample = resample;
        o.dither = dither; o.blocked = blocked; o.brightness = brightness; o.contrast = contrast;
        o.saturation = saturation; o.background = background; o.invisible = invisible; o.glow = glow; o.fixed = fixed;
        return o;
    }

    /** key=value を適用。エラーなら理由、成功なら null。 */
    public static String set(Opts o, String key, String v) {
        try {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "width", "w" -> { o.width = Integer.parseInt(v); if (o.width < 1) return "width は1以上"; }
                case "height", "h" -> { o.height = Integer.parseInt(v); if (o.height < 0) return "height は0以上"; }
                case "mode" -> { if (!List.of("fit", "fill", "stretch").contains(v)) return "mode は fit/fill/stretch"; o.mode = v; }
                case "resample" -> { if (!List.of("nearest", "bilinear", "bicubic").contains(v)) return "resample は nearest/bilinear/bicubic"; o.resample = v; }
                case "dither" -> { if (!List.of("none", "floyd", "ordered").contains(v)) return "dither は none/floyd/ordered"; o.dither = v; }
                case "quality" -> {
                    switch (v) {
                        case "low" -> { o.resample = "nearest"; o.dither = "none"; }
                        case "normal" -> { o.resample = "bilinear"; o.dither = "ordered"; }
                        case "high" -> { o.resample = "bicubic"; o.dither = "floyd"; }
                        default -> { return "quality は low/normal/high"; }
                    }
                }
                case "brightness" -> o.brightness = clamp(Double.parseDouble(v), -1, 1);
                case "contrast" -> o.contrast = clamp(Double.parseDouble(v), 0, 3);
                case "flip" -> {
                switch (v.toLowerCase(Locale.ROOT)) {
                    case "none" -> o.flip = 0;
                    case "h" -> o.flip = 1;
                    case "v" -> o.flip = 2;
                    case "both" -> o.flip = 3;
                    default -> { return "flip は none/h/v/both"; }
                }
            }
            case "saturation" -> o.saturation = clamp(Double.parseDouble(v), 0, 3);
                case "rotate" -> {
                    int r = Integer.parseInt(v);
                    if (r % 90 != 0) return "rotate は 0/90/180/270";
                    o.rotate = ((r % 360) + 360) % 360;
                }
                case "background" -> {
                    if (v.equalsIgnoreCase("transparent") || v.equalsIgnoreCase("none")) o.background = null;
                    else {
                        String h = v.startsWith("#") ? v.substring(1) : v;
                        if (h.length() != 6) return "background は transparent か #RRGGBB";
                        o.background = Integer.parseInt(h, 16);
                    }
                }
                case "invisible" -> o.invisible = bool(v);
                case "glow" -> o.glow = bool(v);
                case "fixed" -> o.fixed = bool(v);
                case "blocked" -> { if (!List.of("abort", "skip").contains(v)) return "blocked は abort/skip"; o.blocked = v; }
                default -> { return "不明な項目: " + key; }
            }
        } catch (NumberFormatException e) {
            return key + " の値が不正: " + v;
        }
        return null;
    }

    private static boolean bool(String v) {
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes");
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    public static List<String> valuesFor(String key) {
        return switch (key) {
            case "mode" -> List.of("fit", "fill", "stretch");
            case "resample" -> List.of("nearest", "bilinear", "bicubic");
            case "dither" -> List.of("none", "floyd", "ordered");
            case "quality" -> List.of("low", "normal", "high");
            case "rotate" -> List.of("0", "90", "180", "270");
            case "flip" -> List.of("none", "h", "v", "both");
            case "background" -> List.of("transparent", "#FFFFFF", "#000000");
            case "invisible", "glow", "fixed" -> List.of("true", "false");
            case "blocked" -> List.of("abort", "skip");
            case "width", "height" -> List.of("1", "2", "4", "8");
            case "brightness" -> List.of("-0.2", "0", "0.2");
            case "contrast", "saturation" -> List.of("0.8", "1", "1.2");
            default -> List.of();
        };
    }
}
