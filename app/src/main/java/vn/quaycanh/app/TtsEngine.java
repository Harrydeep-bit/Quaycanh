package vn.quaycanh.app;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Đọc văn bản thành giọng nói: VieNeu-TTS, OmniVoice (qua Hugging Face) hoặc giọng có sẵn của Android. */
public final class TtsEngine {

    public interface Log { void log(String msg); }

    private TtsEngine() {}

    /**
     * Trả về file âm thanh giọng đọc. style = cách đọc AI gợi ý (vd: "trầm, man mác buồn").
     * Nếu dịch vụ đang chọn lỗi thì tự chuyển sang dịch vụ khác, cuối cùng là giọng Android.
     */
    public static File speak(Context c, Prefs p, String text, String gioiTinh, String style, File outDir, Log log)
            throws Exception {
        String engine = p.ttsEngine();
        String g = "auto".equals(p.voiceGender()) ? gioiTinh : p.voiceGender();
        boolean female = "nu".equals(g);
        java.util.List<String> order = new java.util.ArrayList<>();
        order.add(engine);
        for (String e : new String[]{Prefs.TTS_GEMINI, Prefs.TTS_VIENEU, Prefs.TTS_OMNI})
            if (!order.contains(e)) order.add(e);
        if (Prefs.TTS_ANDROID.equals(engine)) return android(c, text, outDir);
        Exception last = null;
        for (String e : order) {
            try {
                if (Prefs.TTS_GEMINI.equals(e)) {
                    if (p.geminiKey().isEmpty()) continue;
                    return gemini(p, text, female, style, outDir, log);
                }
                if (Prefs.TTS_VIENEU.equals(e)) return vieneu(p, text, female, outDir, log);
                if (Prefs.TTS_OMNI.equals(e)) return omni(p, text, female, outDir, log);
            } catch (Exception ex) {
                last = ex;
                log.log(name(e) + " lỗi: " + Http.shorten(ex.getMessage(), 160) + "\nThử giọng khác…");
            }
        }
        if (last != null) log.log("Giọng online lỗi hết, dùng giọng Android…");
        return android(c, text, outDir);
    }

    static String name(String e) {
        switch (e) {
            case Prefs.TTS_GEMINI: return "Gemini TTS";
            case Prefs.TTS_VIENEU: return "VieNeu";
            case Prefs.TTS_OMNI: return "OmniVoice";
            default: return "Giọng Android";
        }
    }

    // ------------------------------------------------------------- Gemini TTS (có cảm xúc)

    static File gemini(Prefs p, String text, boolean female, String style, File outDir, Log log) throws Exception {
        String voice = female ? p.geminiVoiceFemale() : p.geminiVoiceMale();
        log.log("Đang đọc bằng Gemini TTS (giọng " + voice + ")…");
        String how = (style == null || style.trim().isEmpty())
                ? "trầm ấm, sâu lắng, giàu cảm xúc như người dẫn chuyện trong phim"
                : style.trim();
        String prompt = "Bạn là người đọc truyện tiếng Việt giọng miền Nam, đọc rất chậm rãi và có hồn. "
                + "Đọc đoạn văn dưới đây với giọng " + how + ". "
                + "Nhấn nhá cảm xúc, ngắt nghỉ thật rõ ở mỗi dấu phẩy và dấu chấm, đừng đọc vội. "
                + "Chỉ đọc đúng phần văn bản, không đọc lời dặn này.\n\n" + text;
        org.json.JSONObject body = new org.json.JSONObject()
                .put("contents", new org.json.JSONArray().put(new org.json.JSONObject()
                        .put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", prompt)))))
                .put("generationConfig", new org.json.JSONObject()
                        .put("responseModalities", new org.json.JSONArray().put("AUDIO"))
                        .put("speechConfig", new org.json.JSONObject().put("voiceConfig", new org.json.JSONObject()
                                .put("prebuiltVoiceConfig", new org.json.JSONObject().put("voiceName", voice)))));
        Map<String, String> h = new HashMap<>();
        h.put("x-goog-api-key", p.geminiKey());
        java.util.LinkedHashSet<String> models = new java.util.LinkedHashSet<>();
        if (!p.geminiTtsModel().isEmpty()) models.add(p.geminiTtsModel());
        models.add("gemini-3.1-flash-tts-preview");
        models.add("gemini-2.5-flash-preview-tts");
        models.add("gemini-2.5-pro-preview-tts");
        String res = null;
        Exception lastErr = null;
        for (String m : models) {
            try {
                res = Http.postJson("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent",
                        body.toString(), h, 180000);
                break;
            } catch (IOException e) {
                lastErr = e;
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("API_KEY") || msg.contains("HTTP 401") || msg.contains("HTTP 403")) break;
            }
        }
        if (res == null) throw lastErr != null ? lastErr : new IOException("Gemini TTS không phản hồi");
        org.json.JSONArray parts = new org.json.JSONObject(res).getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        int rate = 24000;
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
            if (inline == null) inline = parts.getJSONObject(i).optJSONObject("inline_data");
            if (inline == null) continue;
            String mime = inline.optString("mimeType", inline.optString("mime_type", ""));
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("rate=(\\d+)").matcher(mime);
            if (mm.find()) rate = Integer.parseInt(mm.group(1));
            pcm.write(android.util.Base64.decode(inline.getString("data"), android.util.Base64.DEFAULT));
        }
        if (pcm.size() < 2000) throw new IOException("Gemini không trả về âm thanh");
        File out = new File(outDir, "giong_gemini.wav");
        AudioUtil.writeWav(out, AudioUtil.fromPcm16(pcm.toByteArray(), rate));
        return out;
    }

    // ------------------------------------------------------------- VieNeu-TTS

    static File vieneu(Prefs p, String text, boolean female, File outDir, Log log) throws Exception {
        String voice = female ? p.vieneuVoiceFemale() : p.vieneuVoiceMale();
        log.log("Đang đọc bằng VieNeu-TTS (giọng " + voice + ")…");
        JSONArray data = new JSONArray()
                .put(text).put(voice).put(JSONObject.NULL)
                .put(0.9).put(40).put(0.95).put(1.15).put(400).put(256);
        return gradio(p.vieneuHost(), new String[]{"synthesize"}, "/synthesize", data, p.hfToken(),
                new File(outDir, "giong_vieneu"), log);
    }

    // ------------------------------------------------------------- OmniVoice

    static File omni(Prefs p, String text, boolean female, File outDir, Log log) throws Exception {
        log.log("Đang đọc bằng OmniVoice (" + (female ? "nữ" : "nam") + ")…");
        JSONArray data = new JSONArray()
                .put(text).put("Vietnamese")
                .put(32).put(2.0).put(true).put(1.0).put(JSONObject.NULL).put(true).put(true)
                .put(female ? "Female / 女" : "Male / 男")
                .put("Middle-aged / 中年")
                .put(female ? "Moderate Pitch / 中音调" : "Low Pitch / 低音调")
                .put("Auto").put("Auto").put("Auto");
        return gradio(p.omniHost(), new String[]{"design"}, "/_design_fn", data, p.hfToken(),
                new File(outDir, "giong_omni"), log);
    }

    // ------------------------------------------------------------- Gradio API

    static File gradio(String host, String[] keywords, String fallbackEndpoint, JSONArray data, String hfToken,
                       File outBase, Log log) throws Exception {
        host = host.replaceAll("/+$", "");
        Map<String, String> h = new HashMap<>();
        if (hfToken != null && !hfToken.isEmpty()) h.put("Authorization", "Bearer " + hfToken);

        String endpoint = fallbackEndpoint;
        try {
            JSONObject info = new JSONObject(Http.get(host + "/gradio_api/info", h, 30000));
            JSONObject named = info.optJSONObject("named_endpoints");
            if (named != null) {
                Iterator<String> it = named.keys();
                outer:
                while (it.hasNext()) {
                    String k = it.next();
                    for (String kw : keywords) if (k.toLowerCase().contains(kw)) { endpoint = k; break outer; }
                }
            }
        } catch (Exception e) {
            log.log("Không đọc được thông tin API, thử gọi thẳng…");
        }

        String body = new JSONObject().put("data", data).toString();
        String res = Http.postJson(host + "/gradio_api/call" + endpoint, body, h, 60000);
        String eventId = new JSONObject(res).getString("event_id");

        HttpURLConnection c = Http.open(host + "/gradio_api/call" + endpoint + "/" + eventId, "GET", h, 300000);
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        String event = null, result = null, error = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if ("complete".equals(event)) { result = d; break; }
                    if ("error".equals(event)) { error = d; break; }
                }
            }
        } finally {
            c.disconnect();
        }
        if (result == null) {
            String msg = error == null || "null".equals(error) ? "máy chủ bận hoặc hết lượt dùng GPU miễn phí" : error;
            throw new IOException(msg);
        }
        JSONArray arr = new JSONArray(result);
        Object first = arr.get(0);
        if (!(first instanceof JSONObject)) {
            String status = arr.length() > 1 ? arr.optString(1) : "";
            throw new IOException("Không có âm thanh trả về. " + status);
        }
        JSONObject fo = (JSONObject) first;
        String url = fo.optString("url", "");
        if (url.isEmpty() || "null".equals(url)) url = host + "/gradio_api/file=" + fo.optString("path");
        String name = fo.optString("orig_name", fo.optString("path", "a.wav"));
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".wav";
        if (ext.length() > 6) ext = ".wav";
        File out = new File(outBase.getAbsolutePath() + ext);
        Http.download(url, h, out);
        return out;
    }

    // ------------------------------------------------------------- Android TTS

    static File android(Context c, String text, File outDir) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Integer> status = new AtomicReference<>(TextToSpeech.ERROR);
        TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(c.getApplicationContext(), st -> { status.set(st); ready.countDown(); });
        TextToSpeech tts = holder[0];
        try {
            if (!ready.await(15, TimeUnit.SECONDS) || status.get() != TextToSpeech.SUCCESS)
                throw new Exception("Máy chưa có bộ đọc chữ (TTS).");
            int r = tts.setLanguage(new Locale("vi", "VN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                throw new Exception("Bộ đọc chữ của máy chưa có tiếng Việt. Vào Cài đặt > Chuyển văn bản thành giọng nói để tải.");
            tts.setSpeechRate(0.9f);
            tts.setPitch(0.95f);
            File out = new File(outDir, "giong_android.wav");
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> err = new AtomicReference<>();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { done.countDown(); }
                @Override public void onError(String id) { err.set("lỗi đọc"); done.countDown(); }
            });
            tts.synthesizeToFile(text, new Bundle(), out, "qc");
            if (!done.await(120, TimeUnit.SECONDS)) throw new Exception("Đọc chữ quá lâu.");
            if (err.get() != null || !out.exists() || out.length() < 1000)
                throw new Exception("Giọng Android không tạo được âm thanh.");
            return out;
        } finally {
            tts.shutdown();
        }
    }
}
