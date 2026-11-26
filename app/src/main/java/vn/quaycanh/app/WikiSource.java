package vn.quaycanh.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Lấy tác phẩm văn học (đã hết bản quyền) từ Wikisource tiếng Việt. */
public final class WikiSource {
    private static final String API = "https://vi.wikisource.org/w/api.php";

    private WikiSource() {}

    public static final class Hit {
        public final String title, snippet;
        Hit(String t, String s) { title = t; snippet = s; }
    }

    public static List<Hit> search(String q) throws Exception {
        String url = API + "?action=query&list=search&srnamespace=0&srlimit=30&format=json&formatversion=2&srsearch="
                + URLEncoder.encode(q, "UTF-8");
        JSONArray a = new JSONObject(Http.get(url, null, 30000)).getJSONObject("query").getJSONArray("search");
        List<Hit> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String sn = android.text.Html.fromHtml(o.optString("snippet")).toString().replaceAll("\\s+", " ");
            out.add(new Hit(o.getString("title"), sn));
        }
        return out;
    }

    /** Các chương con (Tên/Chương 1…). */
    public static List<String> subpages(String title) throws Exception {
        String url = API + "?action=query&list=allpages&apnamespace=0&aplimit=300&format=json&formatversion=2&apprefix="
                + URLEncoder.encode(title + "/", "UTF-8");
        JSONArray a = new JSONObject(Http.get(url, null, 30000)).getJSONObject("query").getJSONArray("allpages");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("title"));
        return out;
    }

    /** Nội dung chữ của một trang. */
    public static String text(String title) throws Exception {
        String url = API + "?action=parse&prop=text&disableeditsection=1&disablelimitreport=1&format=json&formatversion=2&page="
                + URLEncoder.encode(title, "UTF-8");
        JSONObject r = new JSONObject(Http.get(url, null, 60000));
        if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("info"));
        String html = r.getJSONObject("parse").getString("text");
        // bỏ bảng, chú thích, khung điều hướng
        html = html.replaceAll("(?s)<table.*?</table>", " ")
                .replaceAll("(?s)<sup.*?</sup>", "")
                .replaceAll("(?s)<style.*?</style>", "")
                .replaceAll("(?s)<div class=\"(?:ws-noexport|noprint|mw-references-wrap)[^\"]*\".*?</div>", " ")
                .replaceAll("(?i)</p>|<br\\s*/?>|</div>|</h\\d>|</dd>|</li>", " QQPARAQQ ");
        String t = android.text.Html.fromHtml(html).toString();
        t = t.replace("QQPARAQQ", "\n\n").replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").replaceAll("\\n ", "\n").trim();
        return t;
    }
}
