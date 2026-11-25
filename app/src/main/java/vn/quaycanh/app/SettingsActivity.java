package vn.quaycanh.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Cài đặt: khóa AI, giọng đọc, thư mục nhạc. */
public class SettingsActivity extends AppCompatActivity {
    private static final int REQ_FOLDER = 7;
    private static final List<String> MALE = Arrays.asList("Thiện Minh", "Thiền Tâm Đức", "Hải Đăng", "Adam bựa", "Quang Sơn");
    private static final List<String> FEMALE = Arrays.asList("Ngọc Huyền", "Trúc Ly", "Mai Anh", "Thùy Dung", "Ngọc Trân");
    private static final String[] ENGINES = {Prefs.TTS_VIENEU, Prefs.TTS_OMNI, Prefs.TTS_ANDROID};
    private static final String[] GENDERS = {"auto", "nam", "nu"};

    private Prefs p;
    private LinearLayout col;
    private Spinner aiSpin, engineSpin, maleSpin, femaleSpin, genderSpin;
    private EditText geminiKey, geminiModel, claudeKey, claudeModel, hfToken, volume;
    private CheckBox writeOwn, showSource;
    private TextView folderText, testStatus;
    private MediaPlayer mp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 40));
        sv.addView(col);

        TextView title = Ui.label(this, "Cài đặt", 24, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        // ---- AI
        section("AI đạo diễn");
        aiSpin = spinner(new String[]{"Gemini (Google, có gói miễn phí)", "Claude (Anthropic)"},
                Prefs.AI_CLAUDE.equals(p.aiProvider()) ? 1 : 0);
        note("Khóa Gemini miễn phí: vào aistudio.google.com/apikey → Create API key → dán vào đây.");
        geminiKey = field("Khóa Gemini (API key)", p.geminiKey(), true);
        geminiModel = field("Mô hình Gemini", p.geminiModel(), false);
        claudeKey = field("Khóa Claude (API key, console.anthropic.com)", p.claudeKey(), true);
        claudeModel = field("Mô hình Claude", p.claudeModel(), false);
        note("Chưa nhập khóa thì app vẫn chạy, nhưng sẽ chọn văn và nhạc ngẫu nhiên.");
        TextView testAi = Ui.button(this, "Kiểm tra khóa AI", 0xFF2C2F38);
        TextView aiStatus = Ui.label(this, "", 13, Ui.MUTED);
        testAi.setOnClickListener(v -> {
            save();
            aiStatus.setText("Đang gọi thử AI…");
            new Thread(() -> {
                String msg;
                try { msg = "✔ Chạy tốt: " + AiDirector.test(p); }
                catch (Exception e) { msg = "✘ Lỗi: " + Http.shorten(e.getMessage(), 300); }
                String m = msg;
                ui.post(() -> aiStatus.setText(m));
            }).start();
        });
        col.addView(testAi, lp(8));
        col.addView(aiStatus);

        // ---- Giọng
        section("Giọng đọc");
        int ei = Arrays.asList(ENGINES).indexOf(p.ttsEngine());
        engineSpin = spinner(new String[]{"VieNeu-TTS (Hugging Face)", "OmniVoice (Hugging Face)", "Giọng có sẵn của Android"},
                Math.max(0, ei));
        label("Giọng VieNeu nam");
        maleSpin = spinner(MALE.toArray(new String[0]), Math.max(0, MALE.indexOf(p.vieneuVoiceMale())));
        label("Giọng VieNeu nữ");
        femaleSpin = spinner(FEMALE.toArray(new String[0]), Math.max(0, FEMALE.indexOf(p.vieneuVoiceFemale())));
        label("Nam hay nữ");
        genderSpin = spinner(new String[]{"Để AI chọn theo đoạn văn", "Luôn giọng nam", "Luôn giọng nữ"},
                Math.max(0, Arrays.asList(GENDERS).indexOf(p.voiceGender())));
        note("VieNeu và OmniVoice chạy miễn phí trên Hugging Face nhưng giới hạn lượt dùng GPU. "
                + "Nếu hay báo hết lượt: tạo tài khoản huggingface.co → Settings → Access Tokens → tạo token (Read) → dán vào đây. "
                + "Khi dịch vụ online lỗi, app tự chuyển sang giọng khác.");
        hfToken = field("Hugging Face token (không bắt buộc)", p.hfToken(), true);
        TextView test = Ui.button(this, "Nghe thử giọng", 0xFF2C2F38);
        test.setOnClickListener(v -> testVoice());
        col.addView(test, lp(10));
        testStatus = Ui.label(this, "", 13, Ui.MUTED);
        col.addView(testStatus);

        // ---- Nhạc
        section("Nhạc nền");
        note("App có sẵn 4 bản nhạc nền. Muốn dùng nhạc của anh: chép nhạc (mp3…) vào một thư mục trên máy, "
                + "đặt tên file dễ hiểu (vd: \"piano buồn mưa\", \"lofi chill biển\") để AI chọn cho đúng, rồi chọn thư mục đó ở đây.");
        folderText = Ui.label(this, "", 14, 0xFFFFFFFF);
        col.addView(folderText);
        updateFolderText();
        TextView pick = Ui.button(this, "Chọn thư mục nhạc", 0xFF2C2F38);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FOLDER);
        });
        col.addView(pick, lp(8));
        TextView clear = Ui.button(this, "Bỏ thư mục nhạc", 0xFF2C2F38);
        clear.setOnClickListener(v -> { p.put("music_folder", ""); updateFolderText(); });
        col.addView(clear, lp(8));
        volume = field("Độ to nhạc nền (10–80, mặc định 35)", p.musicVolume(), false);
        volume.setInputType(InputType.TYPE_CLASS_NUMBER);

        // ---- Văn
        section("Đoạn văn");
        writeOwn = check("Cho AI tự viết khi kho văn không có đoạn hợp cảnh", p.aiWriteOwn());
        showSource = check("Hiện tên tác phẩm – tác giả ở cuối video", p.showSource());

        setContentView(sv);
    }

    private void section(String s) {
        TextView t = Ui.label(this, s, 18, Ui.RED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    private void label(String s) {
        TextView t = Ui.label(this, s, 13, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        col.addView(t);
    }

    private void note(String s) {
        TextView t = Ui.label(this, s, 13, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    private EditText field(String label, String value, boolean secret) {
        label(label);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(15);
        e.setBackground(Ui.round(0xFF262933, Ui.dp(this, 10)));
        int pd = Ui.dp(this, 10);
        e.setPadding(pd, pd, pd, pd);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        col.addView(e);
        return e;
    }

    private Spinner spinner(String[] items, int sel) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(sel);
        col.addView(s);
        return s;
    }

    private CheckBox check(String text, boolean val) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(0xFFFFFFFF);
        c.setChecked(val);
        col.addView(c);
        return c;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = Ui.dp(this, topDp);
        return l;
    }

    private void updateFolderText() {
        String f = p.musicFolder();
        if (f.isEmpty()) { folderText.setText("Thư mục nhạc: (chưa chọn, chỉ dùng nhạc có sẵn)"); return; }
        try {
            DocumentFile d = DocumentFile.fromTreeUri(this, Uri.parse(f));
            folderText.setText("Thư mục nhạc: " + (d != null ? d.getName() : f));
        } catch (Exception e) {
            folderText.setText("Thư mục nhạc: " + f);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            p.put("music_folder", u.toString());
            updateFolderText();
        }
    }

    private void save() {
        p.put("ai_provider", aiSpin.getSelectedItemPosition() == 1 ? Prefs.AI_CLAUDE : Prefs.AI_GEMINI);
        p.put("gemini_key", geminiKey.getText().toString().trim());
        p.put("gemini_model", geminiModel.getText().toString().trim());
        p.put("claude_key", claudeKey.getText().toString().trim());
        p.put("claude_model", claudeModel.getText().toString().trim());
        p.put("tts_engine", ENGINES[engineSpin.getSelectedItemPosition()]);
        p.put("vieneu_male", MALE.get(maleSpin.getSelectedItemPosition()));
        p.put("vieneu_female", FEMALE.get(femaleSpin.getSelectedItemPosition()));
        p.put("voice_gender", GENDERS[genderSpin.getSelectedItemPosition()]);
        p.put("hf_token", hfToken.getText().toString().trim());
        p.put("music_volume", volume.getText().toString().trim());
        p.putBool("ai_write_own", writeOwn.isChecked());
        p.putBool("show_source", showSource.isChecked());
    }

    private void testVoice() {
        save();
        testStatus.setText("Đang tạo giọng thử…");
        String gender = "nu".equals(p.voiceGender()) ? "nu" : "nam";
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "thu");
                dir.mkdirs();
                File f = TtsEngine.speak(this, p,
                        "Gió vẫn thổi qua đồi cỏ như mười năm trước. Chỉ có con người là đi nhanh quá, vội quá.",
                        gender, dir, msg -> ui.post(() -> testStatus.setText(msg)));
                ui.post(() -> {
                    try {
                        if (mp != null) mp.release();
                        mp = new MediaPlayer();
                        mp.setDataSource(f.getAbsolutePath());
                        mp.prepare();
                        mp.start();
                        testStatus.setText("Đang phát giọng thử.");
                    } catch (Exception e) {
                        testStatus.setText("Không phát được: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                ui.post(() -> testStatus.setText("Lỗi: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
        if (mp != null) { mp.release(); mp = null; }
    }
}
