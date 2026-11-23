package dev.lapius7.imagepaste;

import org.bukkit.configuration.ConfigurationSection;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** 画像の取得。http/https のみ。内部IP・非許可ポート・サイズ超過・遅延を拒否する(SSRF対策)。 */
public final class ImageFetcher {
    public static final class FetchException extends Exception {
        public FetchException(String m) { super(m); }
    }

    private final long maxBytes, maxPixels;
    private final int connectSec, timeoutSec, maxRedirects;
    private final List<Integer> ports;
    private final List<String> hosts;

    public ImageFetcher(ConfigurationSection c) {
        maxBytes = c.getLong("max-bytes", 10L << 20);
        maxPixels = c.getLong("max-source-pixels", 40_000_000L);
        connectSec = c.getInt("connect-timeout-seconds", 5);
        timeoutSec = c.getInt("timeout-seconds", 15);
        maxRedirects = c.getInt("max-redirects", 3);
        ports = c.getIntegerList("allowed-ports").isEmpty() ? List.of(80, 443) : c.getIntegerList("allowed-ports");
        hosts = c.getStringList("allowed-hosts").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        ImageIO.setUseCache(false);
    }

    public BufferedImage fetch(String url) throws FetchException {
        URI uri = parse(url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(connectSec))
                .build();
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos();
        try {
            for (int hop = 0; hop <= maxRedirects; hop++) {
                check(uri);
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new FetchException("タイムアウト");
                HttpRequest req = HttpRequest.newBuilder(uri).GET()
                        .timeout(Duration.ofNanos(left))
                        .header("User-Agent", "ImagePaste/0.1")
                        .header("Accept", "image/*").build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code >= 300 && code < 400 && code != 304) {
                    res.body().close();
                    String loc = res.headers().firstValue("Location").orElseThrow(() -> new FetchException("リダイレクト先が不明"));
                    try { uri = uri.resolve(loc); } catch (IllegalArgumentException e) { throw new FetchException("リダイレクト先が不正"); }
                    continue;
                }
                if (code != 200) { res.body().close(); throw new FetchException("HTTP " + code); }
                long len = res.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (len > maxBytes) { res.body().close(); throw new FetchException("ファイルが大きすぎます"); }
                return decode(readLimited(res.body(), deadline));
            }
            throw new FetchException("リダイレクトが多すぎます");
        } catch (IOException e) {
            throw new FetchException("通信エラー: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("中断されました");
        }
    }

    private byte[] readLimited(InputStream in, long deadline) throws IOException, FetchException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > maxBytes) throw new FetchException("ファイルが大きすぎます");
                if (System.nanoTime() > deadline) throw new FetchException("タイムアウト");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private BufferedImage decode(byte[] data) throws FetchException, IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) throw new FetchException("未対応の画像形式です (png/jpg/gif/bmp)");
            ImageReader r = it.next();
            try {
                r.setInput(iis, true, true);
                long w = r.getWidth(0), h = r.getHeight(0);
                if ((maxPixels > 0 && (long) w * h > maxPixels) || (maxPixels > 0 && (w > 20000 || h > 20000))) throw new FetchException("画像の解像度が大きすぎます");
                BufferedImage img = r.read(0);
                if (img == null) throw new FetchException("画像を読めません");
                return img;
            } finally { r.dispose(); }
        }
    }

    private URI parse(String url) throws FetchException {
        URI u;
        try { u = new URI(url.trim()); } catch (URISyntaxException e) { throw new FetchException("URLが不正です"); }
        String s = u.getScheme();
        if (s == null || !(s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))) throw new FetchException("http/https のURLのみ使えます");
        return u;
    }

    private void check(URI u) throws FetchException {
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!s.equals("http") && !s.equals("https")) throw new FetchException("http/https のURLのみ使えます");
        if (u.getUserInfo() != null) throw new FetchException("認証情報つきURLは使えません");
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new FetchException("ホストがありません");
        int port = u.getPort() >= 0 ? u.getPort() : (s.equals("https") ? 443 : 80);
        if (!ports.contains(port)) throw new FetchException("許可されていないポートです: " + port);
        String h = host.toLowerCase(Locale.ROOT);
        if (!hosts.isEmpty() && hosts.stream().noneMatch(a -> h.equals(a) || h.endsWith("." + a)))
            throw new FetchException("許可されていないホストです");
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(host); } catch (UnknownHostException e) { throw new FetchException("ホストを解決できません"); }
        for (InetAddress a : addrs) if (!isPublic(a)) throw new FetchException("内部アドレスへのアクセスは拒否されます");
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0) return false;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;       // CGNAT
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;      // ベンチマーク用
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (b0 >= 240) return false;
            return true;
        }
        if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return false;                    // fc00::/7
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return false; // ::ffff:0:0/96
            boolean compat = true;
            for (int i = 0; i < 12; i++) if (b[i] != 0) compat = false;
            if (compat) return false;                                    // ::/96
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8) return false; // 文書用
            if (b[0] == 0x00 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b) return false; // NAT64
        }
        return true;
    }
}
