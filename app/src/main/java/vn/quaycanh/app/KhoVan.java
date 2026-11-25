package vn.quaycanh.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Kho văn: các đoạn trích anh tự thêm + vài đoạn văn mẫu. Lưu ở files/kho_van.json. */
public final class KhoVan {

    public static final class Doan {
        public String van;
        public String nguon;
        public String the;

        public Doan(String van, String nguon, String the) {
            this.van = van == null ? "" : van.trim();
            this.nguon = nguon == null ? "" : nguon.trim();
            this.the = the == null ? "" : the.trim();
        }
    }

    private KhoVan() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "kho_van.json");
    }

    public static synchronized List<Doan> load(Context c) {
        List<Doan> list = new ArrayList<>();
        try {
            String json;
            File f = file(c);
            if (f.exists()) {
                json = new String(Http.readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
            } else {
                json = new String(Http.readAll(c.getAssets().open("kho_van_mau.json")), StandardCharsets.UTF_8);
            }
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Doan d = new Doan(o.optString("van"), o.optString("nguon"), o.optString("the"));
                if (!d.van.isEmpty()) list.add(d);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static synchronized void save(Context c, List<Doan> list) {
        try {
            JSONArray a = new JSONArray();
            for (Doan d : list) {
                JSONObject o = new JSONObject();
                o.put("van", d.van);
                o.put("nguon", d.nguon);
                o.put("the", d.the);
                a.put(o);
            }
            try (FileOutputStream os = new FileOutputStream(file(c))) {
                os.write(a.toString(1).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Tách văn bản dán vào thành nhiều đoạn. Mỗi đoạn cách nhau bằng một dòng trống.
     * Dòng bắt đầu bằng "—", "--" hoặc "Nguồn:" là tên tác phẩm / tác giả của đoạn đó.
     * Dòng bắt đầu bằng "#" là thẻ (vd: # biển, chiều, buồn).
     */
    public static List<Doan> parse(String text, String defaultNguon) {
        List<Doan> out = new ArrayList<>();
        if (text == null) return out;
        String[] blocks = text.replace("\r", "").split("\\n\\s*\\n");
        for (String b : blocks) {
            StringBuilder van = new StringBuilder();
            String nguon = defaultNguon;
            String the = "";
            for (String line : b.split("\\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("—") || t.startsWith("--") || t.startsWith("–")) {
                    nguon = t.replaceFirst("^[—–-]+\\s*", "");
                } else if (t.toLowerCase().startsWith("nguồn:")) {
                    nguon = t.substring(6).trim();
                } else if (t.startsWith("#")) {
                    the = t.substring(1).trim();
                } else {
                    if (van.length() > 0) van.append(' ');
                    van.append(t);
                }
            }
            if (van.length() > 0) out.add(new Doan(van.toString(), nguon, the));
        }
        return out;
    }
}
