package vn.quaycanh.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Gọi HTTP đơn giản bằng HttpURLConnection. */
public final class Http {
    private Http() {}

    public static HttpURLConnection open(String url, String method, Map<String, String> headers,
                                         int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("User-Agent", "QuayCanh-Android/1.0");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) c.setRequestProperty(e.getKey(), e.getValue());
        }
        return c;
    }

    public static String postJson(String url, String body, Map<String, String> headers, int readTimeoutMs)
            throws IOException {
        HttpURLConnection c = open(url, "POST", headers, readTimeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(c);
    }

    public static String get(String url, Map<String, String> headers, int readTimeoutMs) throws IOException {
        return readResponse(open(url, "GET", headers, readTimeoutMs));
    }

    static String readResponse(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
        c.disconnect();
        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + shorten(text, 400));
        }
        return text;
    }

    public static void download(String url, Map<String, String> headers, File out) throws IOException {
        HttpURLConnection c = open(url, "GET", headers, 120000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code >= 400) {
            InputStream es = c.getErrorStream();
            String t = es == null ? "" : new String(readAll(es), StandardCharsets.UTF_8);
            throw new IOException("Tải file lỗi HTTP " + code + ": " + shorten(t, 300));
        }
        try (InputStream is = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = is.read(b)) > 0) os.write(b, 0, n);
        } finally {
            c.disconnect();
        }
    }

    public static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        is.close();
        return bo.toByteArray();
    }

    public static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
