package vn.quaycanh.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** "Đạo diễn" AI: nhìn cảnh, chọn đoạn văn, chọn nhạc, chọn giọng. */
public final class AiDirector {

    public static final class Plan {
        public String moTa = "";
        public String tamTrang = "";
        public int doanIndex = -1;     // -1 = AI tự viết
        public String van = "";
        public String nguon = "";
        public String musicId = "";
        public String gioiTinh = "nam"; // nam / nu
    }

    private AiDirector() {}

    /** Số chữ (âm tiết) nên đọc cho vừa độ dài clip. */
    public static int[] wordRange(double videoSec) {
        double speak = Math.max(6, Math.min(videoSec, 60) - 2.5);
        int lo = (int) Math.max(14, speak * 2.4);
        int hi = (int) Math.max(24, speak * 3.3);
        return new int[]{lo, hi};
    }

    public static Plan plan(Prefs p, List<String> jpegBase64, double videoSec, List<KhoVan.Doan> kho,
                            List<MusicLib.Track> music, Set<Integer> daDung, String ghiChu) throws Exception {
        String provider = p.aiProvider();
        boolean hasKey = Prefs.AI_CLAUDE.equals(provider) ? !p.claudeKey().isEmpty() : !p.geminiKey().isEmpty();
        if (!hasKey) return offline(kho, music, daDung);

        String prompt = buildPrompt(p, videoSec, kho, music, daDung, ghiChu);
        String text = Prefs.AI_CLAUDE.equals(provider)
                ? callClaude(p, prompt, jpegBase64)
                : callGemini(p, prompt, jpegBase64);
        return parse(text, kho, music);
    }

    static String buildPrompt(Prefs p, double videoSec, List<KhoVan.Doan> kho, List<MusicLib.Track> music,
                              Set<Integer> daDung, String ghiChu) {
        int[] wr = wordRange(videoSec);
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là đạo diễn của một app làm video ngắn kiểu \"sống ảo\" trên TikTok/Reels: ")
          .append("người dùng quay một cảnh đẹp (biển, núi, phố, nhà cửa, con vật...), app ghép thêm ")
          .append("một đoạn văn mang chất tiểu thuyết được đọc bằng giọng trầm ấm, nhạc nền và phụ đề chạy chữ.\n\n")
          .append("Các ảnh đính kèm là khung hình trích từ đoạn video vừa quay (dài khoảng ")
          .append(Math.round(videoSec)).append(" giây).\n\n")
          .append("VIỆC CỦA BẠN:\n")
          .append("1. Nhìn kỹ cảnh: đó là gì, ánh sáng, thời điểm trong ngày, không khí, cảm xúc gợi ra.\n")
          .append("2. Chọn trong KHO VĂN bên dưới đoạn hợp nhất với cảnh và cảm xúc đó. ")
          .append("Có thể cắt bớt đoạn đã chọn (giữ nguyên câu chữ gốc, chỉ bỏ bớt câu ở đầu/cuối) cho vừa độ dài.\n");
        if (p.aiWriteOwn()) {
            sb.append("   Nếu KHÔNG có đoạn nào thật sự hợp, hãy TỰ VIẾT một đoạn mới (doan_id = -1) theo giọng văn tiểu thuyết ")
              .append("Việt Nam: giàu hình ảnh, chiêm nghiệm, man mác, câu chữ tự nhiên, không sáo rỗng, không dùng emoji. ")
              .append("Đoạn tự viết phải là văn của bạn, không chép lại văn của tác giả khác.\n");
        } else {
            sb.append("   Luôn phải chọn một đoạn trong kho (không tự viết).\n");
        }
        sb.append("3. Độ dài văn bản cuối cùng: khoảng ").append(wr[0]).append("–").append(wr[1])
          .append(" chữ (âm tiết), 2–4 câu, để đọc chậm vừa khít video.\n")
          .append("4. Chọn 1 bản nhạc trong KHO NHẠC hợp không khí (dựa vào tên/mô tả).\n")
          .append("5. Chọn giọng đọc nam hay nữ hợp với đoạn văn.\n\n");
        if (ghiChu != null && !ghiChu.trim().isEmpty()) {
            sb.append("GHI CHÚ CỦA NGƯỜI DÙNG (ưu tiên làm theo): ").append(ghiChu.trim()).append("\n\n");
        }
        sb.append("KHO VĂN (id | nguồn | thẻ | nội dung):\n");
        int count = 0;
        for (int i = 0; i < kho.size() && count < 200; i++) {
            if (daDung != null && daDung.contains(i)) continue;
            KhoVan.Doan d = kho.get(i);
            String v = d.van.length() > 600 ? d.van.substring(0, 600) + "…" : d.van;
            sb.append(i).append(" | ").append(d.nguon).append(" | ").append(d.the).append(" | ").append(v).append('\n');
            count++;
        }
        if (count == 0) sb.append("(trống)\n");
        sb.append("\nKHO NHẠC (id | mô tả):\n");
        for (MusicLib.Track t : music) sb.append(t.id).append(" | ").append(t.mood).append('\n');
        sb.append("\nCHỈ trả về đúng một đối tượng JSON, không thêm chữ nào khác:\n")
          .append("{\"mo_ta\": \"mô tả cảnh ngắn gọn\", \"tam_trang\": \"vài chữ\", ")
          .append("\"doan_id\": số id trong kho hoặc -1, ")
          .append("\"van_ban\": \"văn bản cuối cùng sẽ được đọc\", ")
          .append("\"nguon\": \"tên tác phẩm – tác giả của đoạn trong kho, hoặc chuỗi rỗng nếu tự viết\", ")
          .append("\"nhac_id\": \"id bản nhạc\", \"gioi_tinh\": \"nam\" hoặc \"nu\"}");
        return sb.toString();
    }

    static String callGemini(Prefs p, String prompt, List<String> imgs) throws Exception {
        JSONArray parts = new JSONArray();
        for (String b64 : imgs) {
            parts.put(new JSONObject().put("inline_data",
                    new JSONObject().put("mime_type", "image/jpeg").put("data", b64)));
        }
        parts.put(new JSONObject().put("text", prompt));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("temperature", 0.9));
        Map<String, String> h = new HashMap<>();
        h.put("x-goog-api-key", p.geminiKey());
        String model = p.geminiModel().isEmpty() ? "gemini-flash-latest" : p.geminiModel();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        String res = Http.postJson(url, body.toString(), h, 120000);
        JSONObject r = new JSONObject(res);
        JSONArray cands = r.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("Gemini không trả lời: " + Http.shorten(res, 300));
        JSONArray ps = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject o = ps.getJSONObject(i);
            if (o.optBoolean("thought", false)) continue;
            sb.append(o.optString("text"));
        }
        return sb.toString();
    }

    static String callClaude(Prefs p, String prompt, List<String> imgs) throws Exception {
        JSONArray content = new JSONArray();
        for (String b64 : imgs) {
            content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)));
        }
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        JSONObject body = new JSONObject()
                .put("model", p.claudeModel().isEmpty() ? "claude-haiku-5-5" : p.claudeModel())
                .put("max_tokens", 1500)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        Map<String, String> h = new HashMap<>();
        h.put("x-api-key", p.claudeKey());
        h.put("anthropic-version", "2023-06-01");
        String res = Http.postJson("https://api.anthropic.com/v1/messages", body.toString(), h, 120000);
        JSONArray c = new JSONObject(res).getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            JSONObject o = c.getJSONObject(i);
            if ("text".equals(o.optString("type"))) sb.append(o.optString("text"));
        }
        return sb.toString();
    }

    static Plan parse(String text, List<KhoVan.Doan> kho, List<MusicLib.Track> music) throws Exception {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new Exception("AI trả lời không đúng dạng: " + Http.shorten(text, 200));
        JSONObject o = new JSONObject(text.substring(a, b + 1));
        Plan pl = new Plan();
        pl.moTa = o.optString("mo_ta");
        pl.tamTrang = o.optString("tam_trang");
        pl.doanIndex = o.optInt("doan_id", -1);
        pl.van = o.optString("van_ban").trim();
        pl.nguon = o.optString("nguon").trim();
        pl.musicId = o.optString("nhac_id");
        pl.gioiTinh = o.optString("gioi_tinh", "nam").toLowerCase().startsWith("n\u1eef")
                || o.optString("gioi_tinh").equalsIgnoreCase("nu") ? "nu" : "nam";
        if (pl.doanIndex >= 0 && pl.doanIndex < kho.size()) {
            KhoVan.Doan d = kho.get(pl.doanIndex);
            if (pl.van.isEmpty()) pl.van = d.van;
            if (pl.nguon.isEmpty()) pl.nguon = d.nguon;
        } else {
            pl.doanIndex = -1;
        }
        if (MusicLib.find(music, pl.musicId) == null && !music.isEmpty()) pl.musicId = music.get(0).id;
        if (pl.van.isEmpty()) throw new Exception("AI không đưa ra đoạn văn nào.");
        return pl;
    }

    /** Khi chưa nhập khóa AI: chọn ngẫu nhiên trong kho. */
    static Plan offline(List<KhoVan.Doan> kho, List<MusicLib.Track> music, Set<Integer> daDung) {
        Random r = new Random();
        Plan pl = new Plan();
        pl.moTa = "(Chưa có khóa AI — chọn ngẫu nhiên)";
        if (!kho.isEmpty()) {
            int i = r.nextInt(kho.size());
            for (int k = 0; k < kho.size() && daDung != null && daDung.contains(i); k++) i = (i + 1) % kho.size();
            pl.doanIndex = i;
            pl.van = kho.get(i).van;
            pl.nguon = kho.get(i).nguon;
        } else {
            pl.van = "Có những khoảnh khắc chẳng cần nói gì, chỉ cần đứng lặng mà nhìn.";
        }
        if (!music.isEmpty()) pl.musicId = music.get(r.nextInt(music.size())).id;
        return pl;
    }
}
