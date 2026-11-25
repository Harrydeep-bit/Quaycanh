package vn.quaycanh.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

/** Kho văn: xem, thêm, sửa, xoá các đoạn trích để AI chọn. */
public class KhoVanActivity extends AppCompatActivity {
    private static final int REQ_TXT = 3;
    private List<KhoVan.Doan> list;
    private LinearLayout items;
    private TextView count;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        list = KhoVan.load(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 40));
        sv.addView(col);

        TextView title = Ui.label(this, "Kho văn", 24, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView help = Ui.label(this,
                "AI sẽ chọn trong kho này đoạn hợp với cảnh anh quay. Anh dán các trích đoạn tiểu thuyết anh thích vào đây. "
                        + "Dán nhiều đoạn một lần cũng được: mỗi đoạn cách nhau một dòng trống, dòng bắt đầu bằng \"—\" là tên tác phẩm – tác giả, "
                        + "dòng bắt đầu bằng \"#\" là thẻ (vd: # biển, chiều, buồn).", 13, Ui.MUTED);
        help.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(help);

        TextView add = Ui.button(this, "+ Thêm đoạn", Ui.RED);
        add.setOnClickListener(v -> editDialog(-1));
        col.addView(add, lp(6));
        TextView imp = Ui.button(this, "Nhập từ file .txt", 0xFF2C2F38);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("text/*");
            startActivityForResult(Intent.createChooser(i, "Chọn file .txt"), REQ_TXT);
        });
        col.addView(imp, lp(8));
        TextView delSample = Ui.button(this, "Xoá các đoạn văn mẫu", 0xFF2C2F38);
        delSample.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Xoá hết các đoạn \"Văn mẫu\" có sẵn, chỉ giữ đoạn anh tự thêm?")
                .setPositiveButton("Xoá", (d, w) -> {
                    Iterator<KhoVan.Doan> it = list.iterator();
                    while (it.hasNext()) if ("Văn mẫu".equals(it.next().nguon)) it.remove();
                    saveAndRefresh();
                })
                .setNegativeButton("Thôi", null).show());
        col.addView(delSample, lp(8));

        count = Ui.label(this, "", 14, 0xFFFFFFFF);
        count.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        col.addView(count);
        items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        col.addView(items);
        refresh();
        setContentView(sv);
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = Ui.dp(this, topDp);
        return l;
    }

    private void refresh() {
        items.removeAllViews();
        count.setText(list.size() + " đoạn trong kho");
        for (int i = 0; i < list.size(); i++) {
            KhoVan.Doan d = list.get(i);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(this, 12);
            c.setPadding(p, p, p, p);
            c.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 12)));
            TextView v = Ui.label(this, d.van.length() > 220 ? d.van.substring(0, 220) + "…" : d.van, 15, 0xFFFFFFFF);
            c.addView(v);
            String meta = (d.nguon.isEmpty() ? "(không rõ nguồn)" : "— " + d.nguon) + (d.the.isEmpty() ? "" : "   # " + d.the);
            TextView m = Ui.label(this, meta, 12, Ui.MUTED);
            m.setPadding(0, Ui.dp(this, 6), 0, 0);
            c.addView(m);
            final int idx = i;
            c.setOnClickListener(x -> editDialog(idx));
            items.addView(c, lp(8));
        }
    }

    private void saveAndRefresh() {
        KhoVan.save(this, list);
        refresh();
    }

    private EditText input(String hint, String val, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(5);
            e.setMaxLines(12);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private void editDialog(int idx) {
        KhoVan.Doan d = idx >= 0 ? list.get(idx) : null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText van = input(idx >= 0 ? "Đoạn văn" : "Dán một hoặc nhiều đoạn văn vào đây", d == null ? "" : d.van, true);
        EditText nguon = input("Tác phẩm – Tác giả", d == null ? "" : d.nguon, false);
        EditText the = input("Thẻ: biển, chiều, buồn… (không bắt buộc)", d == null ? "" : d.the, false);
        box.addView(van);
        box.addView(nguon);
        box.addView(the);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder ab = new AlertDialog.Builder(this)
                .setTitle(idx >= 0 ? "Sửa đoạn" : "Thêm đoạn")
                .setView(sv)
                .setPositiveButton("Lưu", (dlg, w) -> {
                    String text = van.getText().toString();
                    if (idx >= 0) {
                        d.van = text.trim().replaceAll("\\s*\\n\\s*", " ");
                        d.nguon = nguon.getText().toString().trim();
                        d.the = the.getText().toString().trim();
                        if (d.van.isEmpty()) list.remove(idx);
                    } else {
                        List<KhoVan.Doan> parsed = KhoVan.parse(text, nguon.getText().toString().trim());
                        String tags = the.getText().toString().trim();
                        for (KhoVan.Doan x : parsed) if (x.the.isEmpty()) x.the = tags;
                        list.addAll(0, parsed);
                        Toast.makeText(this, "Đã thêm " + parsed.size() + " đoạn", Toast.LENGTH_SHORT).show();
                    }
                    saveAndRefresh();
                })
                .setNegativeButton("Huỷ", null);
        if (idx >= 0) ab.setNeutralButton("Xoá", (dlg, w) -> { list.remove(idx); saveAndRefresh(); });
        ab.show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TXT && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try (InputStream is = getContentResolver().openInputStream(u)) {
                String text = new String(Http.readAll(is), StandardCharsets.UTF_8);
                List<KhoVan.Doan> parsed = KhoVan.parse(text, "");
                list.addAll(0, parsed);
                saveAndRefresh();
                Toast.makeText(this, "Đã nhập " + parsed.size() + " đoạn", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Không đọc được file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
