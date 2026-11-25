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

    public static Track find(List<Track> list, String id) {
        if (id != null) for (Track t : list) if (t.id.equals(id)) return t;
        return null;
    }
}
