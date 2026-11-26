package vn.quaycanh.app;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

/** Kho nhạc: 4 bản nhạc nền có sẵn + nhạc trong thư mục anh chọn trên máy. */
public final class MusicLib {

    public static final class Track {
        public final String id;      // để AI chọn
        public final String name;    // tên hiển thị
        public final String mood;    // mô tả cho AI
        public final String asset;   // đường dẫn trong assets (nhạc có sẵn), hoặc null
        public final Uri uri;        // file trên máy, hoặc null

        Track(String id, String name, String mood, String asset, Uri uri) {
            this.id = id; this.name = name; this.mood = mood; this.asset = asset; this.uri = uri;
        }
    }

    private MusicLib() {}

    public static List<Track> list(Context c, Prefs p) {
        List<Track> out = new ArrayList<>();
        String folder = p.musicFolder();
        if (!folder.isEmpty()) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(c, Uri.parse(folder));
                if (dir != null) collect(dir, out, 0);
            } catch (Exception ignored) {
            }
        }
        if (!p.jamendoId().isEmpty()) {
            out.add(0, new Track("jamendo", "Jamendo · AI tìm nhạc online theo cảnh",
                    "nhạc online Jamendo (kho hơn 500.000 bài miễn phí bản quyền): app sẽ tìm theo nhac_tags — NÊN CHỌN khi không có bài nào ở trên thật hợp",
                    null, null));
        }
        out.add(new Track("co_san_binh_yen", "Có sẵn · Bình yên", "nhạc nền có sẵn: bình yên, nhẹ nhàng, thư thái, piano + pad",
                "nhac/binh_yen.m4a", null));
        out.add(new Track("co_san_hoai_niem", "Có sẵn · Hoài niệm", "nhạc nền có sẵn: buồn, hoài niệm, chậm, man mác",
                "nhac/hoai_niem.m4a", null));
        out.add(new Track("co_san_hung_vi", "Có sẵn · Hùng vĩ", "nhạc nền có sẵn: trầm, rộng lớn, hùng vĩ, núi non biển cả",
                "nhac/hung_vi.m4a", null));
        out.add(new Track("co_san_vui_tuoi", "Có sẵn · Vui tươi", "nhạc nền có sẵn: vui tươi, trong trẻo, buổi sáng, con vật dễ thương",
                "nhac/vui_tuoi.m4a", null));
        return out;
    }

    private static void collect(DocumentFile dir, List<Track> out, int depth) {
        if (depth > 2 || out.size() > 300) return;
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
                continue;
            }
            String n = f.getName();
            if (n == null) continue;
            String lower = n.toLowerCase();
            if (!(lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                    || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".flac")
                    || lower.endsWith(".opus"))) continue;
            String name = n.substring(0, n.lastIndexOf('.'));
            String folderName = depth > 0 && dir.getName() != null ? dir.getName() + " / " : "";
            out.add(new Track("may_" + out.size(), name,
                    "nhạc của anh: " + folderName + name, null, f.getUri()));
        }
    }

    /** Kết quả tìm nhạc Jamendo: file đã tải + dòng ghi công tác giả. */
    public static final class Online {
        public final java.io.File file;
        public final String credit;
        Online(java.io.File f, String c) { file = f; credit = c; }
    }

    /** Tìm và tải 1 bài nhạc không lời trên Jamendo theo từ khoá tiếng Anh. */
    public static Online jamendo(Prefs p, String tags, java.io.File dir) throws Exception {
        String q = (tags == null || tags.trim().isEmpty()) ? "calm piano" : tags.trim();
        java.util.List<String> tries = new java.util.ArrayList<>();
        tries.add(q);
        String[] words = q.split("[\\s,]+");
        if (words.length > 1) tries.add(words[0]);
        tries.add("ambient");
        for (String t : tries) {
            String url = "https://api.jamendo.com/v3.0/tracks/?client_id=" + java.net.URLEncoder.encode(p.jamendoId(), "UTF-8")
                    + "&format=json&limit=20&vocalinstrumental=instrumental&audioformat=mp32&order=popularity_total"
                    + "&durationbetween=60_600&fuzzytags=" + java.net.URLEncoder.encode(t.replaceAll("[\\s,]+", "+"), "UTF-8").replace("%2B", "+");
            org.json.JSONObject r = new org.json.JSONObject(Http.get(url, null, 30000));
            org.json.JSONObject hd = r.optJSONObject("headers");
            if (hd != null && !"success".equals(hd.optString("status")))
                throw new Exception("Jamendo: " + hd.optString("error_message"));
            org.json.JSONArray res = r.optJSONArray("results");
            if (res == null || res.length() == 0) continue;
            java.util.List<org.json.JSONObject> ok = new java.util.ArrayList<>();
            for (int i = 0; i < res.length(); i++) {
                org.json.JSONObject o = res.getJSONObject(i);
                if (!o.optString("audio").isEmpty()) ok.add(o);
            }
            if (ok.isEmpty()) continue;
            org.json.JSONObject pick = ok.get(new java.util.Random().nextInt(Math.min(ok.size(), 8)));
            java.io.File f = new java.io.File(dir, "jamendo.mp3");
            Http.download(pick.getString("audio"), null, f);
            return new Online(f, pick.optString("name") + " – " + pick.optString("artist_name") + " (Jamendo, CC)");
        }
        throw new Exception("Jamendo không tìm thấy bài hợp");
    }

    public static Track find(List<Track> list, String id) {
        if (id != null) for (Track t : list) if (t.id.equals(id)) return t;
        return null;
    }
}
