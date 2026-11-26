package vn.quaycanh.app;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.media3.common.util.UnstableApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Màn hình xử lý: AI chọn văn + nhạc → anh sửa nếu muốn → tạo video → xem / chia sẻ. */
@UnstableApi
public class ProcessActivity extends AppCompatActivity {
    private static final double LEAD = 1.2; // giây im lặng trước khi bắt đầu đọc

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private File video;
    private double videoSec;
    private final List<String> frames = new ArrayList<>();
    private List<KhoVan.Doan> kho;
    private List<MusicLib.Track> music;
    private final Set<Integer> daDung = new HashSet<>();
    private AiDirector.Plan plan;
    private VideoMaker maker;
    private File resultFile;
    private Uri galleryUri;
    private volatile String note = "";
    private volatile String musicCredit = "";

    private TextView status;
    private ProgressBar spinner;
    private LinearLayout planCard, resultCard;
    private TextView sceneText;
    private EditText vanEdit, nguonEdit, noteEdit;
    private Spinner musicSpin, voiceSpin;
    private VideoView player;
    private ImageView thumb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        video = new File(getIntent().getStringExtra("video"));
        buildUi();
        bg.execute(this::analyze);
    }

    // ------------------------------------------------------------------ giao diện

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 20), p, Ui.dp(this, 40));
        sv.addView(col);

        TextView title = Ui.label(this, "Đang dựng video", 22, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 200));
        tp.topMargin = Ui.dp(this, 12);
        col.addView(thumb, tp);

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this);
        st.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        status = Ui.label(this, "Đang xem cảnh…", 15, 0xFFE6E6E6);
        status.setPadding(Ui.dp(this, 10), 0, 0, 0);
        st.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 14);
        col.addView(st, sp);

        // ---- thẻ kế hoạch
        planCard = card();
        sceneText = Ui.label(this, "", 14, Ui.MUTED);
        planCard.addView(sceneText);
        planCard.addView(caption("Đoạn văn sẽ đọc (sửa được)"));
        vanEdit = edit(true);
        planCard.addView(vanEdit);
        planCard.addView(caption("Nguồn (tác phẩm – tác giả, hiện cuối video)"));
        nguonEdit = edit(false);
        planCard.addView(nguonEdit);
        planCard.addView(caption("Nhạc nền"));
        musicSpin = new Spinner(this);
        planCard.addView(musicSpin);
        planCard.addView(caption("Giọng đọc"));
        voiceSpin = new Spinner(this);
        ArrayAdapter<String> va = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Giọng nam", "Giọng nữ"});
        voiceSpin.setAdapter(va);
        planCard.addView(voiceSpin);

        TextView make = Ui.button(this, "Tạo video", Ui.RED);
        make.setOnClickListener(v -> makeVideo());
        planCard.addView(make, margins(-1, 18));

        planCard.addView(caption("Muốn đổi? Ghi chú cho AI (vd: buồn hơn, nói về mẹ…)"));
        noteEdit = edit(false);
        planCard.addView(noteEdit);
        TextView again = Ui.button(this, "Đổi đoạn khác", 0xFF2C2F38);
        again.setOnClickListener(v -> {
            if (plan != null && plan.doanIndex >= 0) daDung.add(plan.doanIndex);
            note = noteEdit.getText().toString();
            planCard.setVisibility(View.GONE);
            setBusy(true, "AI đang chọn đoạn khác…");
            bg.execute(this::askAi);
        });
        planCard.addView(again, margins(-1, 10));
        planCard.setVisibility(View.GONE);
        col.addView(planCard, margins(-1, 14));

        // ---- thẻ kết quả
        resultCard = card();
        FrameLayout pf = new FrameLayout(this);
        player = new VideoView(this);
        pf.addView(player, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        resultCard.addView(pf, new LinearLayout.LayoutParams(-1, Ui.dp(this, 520)));
        TextView share = Ui.button(this, "Chia sẻ video", Ui.RED);
        share.setOnClickListener(v -> share());
        resultCard.addView(share, margins(-1, 14));
        TextView redo = Ui.button(this, "Làm lại với đoạn khác", 0xFF2C2F38);
        redo.setOnClickListener(v -> {
            resultCard.setVisibility(View.GONE);
            player.stopPlayback();
            if (plan != null && plan.doanIndex >= 0) daDung.add(plan.doanIndex);
            note = noteEdit.getText().toString();
            resultFile = null;
            galleryUri = null;
            setBusy(true, "AI đang chọn đoạn khác…");
            bg.execute(this::askAi);
        });
        resultCard.addView(redo, margins(-1, 10));
        TextView fresh = Ui.button(this, "Quay cảnh mới", 0xFF2C2F38);
        fresh.setOnClickListener(v -> finish());
        resultCard.addView(fresh, margins(-1, 10));
        resultCard.setVisibility(View.GONE);
        col.addView(resultCard, margins(-1, 14));

        setContentView(sv);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        c.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));
        return c;
    }

    private TextView caption(String s) {
        TextView t = Ui.label(this, s, 13, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        return t;
    }

    private EditText edit(boolean multi) {
        EditText e = new EditText(this);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(16);
        e.setBackground(Ui.round(0xFF262933, Ui.dp(this, 10)));
        int p = Ui.dp(this, 10);
        e.setPadding(p, p, p, p);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(4);
            e.setGravity(Gravity.TOP);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private LinearLayout.LayoutParams margins(int w, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private void setBusy(boolean busy, String msg) {
        ui.post(() -> {
            spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
            status.setText(msg);
        });
    }

    private void log(String msg) {
        ui.post(() -> status.setText(msg));
    }

    // ------------------------------------------------------------------ bước 1: xem cảnh + AI

    private void analyze() {
        try {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            r.setDataSource(video.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            videoSec = d == null ? 10 : Long.parseLong(d) / 1000.0;
            double[] at = {0.15, 0.5, 0.85};
            for (int i = 0; i < at.length; i++) {
                Bitmap bm = r.getFrameAtTime((long) (videoSec * at[i] * 1_000_000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bm == null) continue;
                Bitmap sm = scale(bm, 768);
                if (i == 1) {
                    Bitmap show = sm;
                    ui.post(() -> thumb.setImageBitmap(show));
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                sm.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                frames.add(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
            }
            r.release();
            kho = KhoVan.load(this);
            music = MusicLib.list(this, prefs);
            ui.post(() -> {
                List<String> names = new ArrayList<>();
                for (MusicLib.Track t : music) names.add(t.name);
                musicSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            });
        } catch (Exception e) {
            setBusy(false, "Không đọc được video: " + e.getMessage());
            return;
        }
        askAi();
    }

    private static Bitmap scale(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight();
        float s = Math.min(1f, max / (float) Math.max(w, h));
        if (s >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.round(w * s), Math.round(h * s), true);
    }

    private void askAi() {
        String provider = Prefs.AI_CLAUDE.equals(prefs.aiProvider()) ? "Claude" : "Gemini";
        setBusy(true, "AI (" + provider + ") đang ngắm cảnh và chọn văn, chọn nhạc…");
        try {
            AiDirector.Plan pl = AiDirector.plan(prefs, frames, videoSec, kho, music, daDung, note);
            ui.post(() -> showPlan(pl));
        } catch (Exception e) {
            AiDirector.Plan pl = AiDirector.offline(kho, music, daDung);
            pl.moTa = "⚠ AI LỖI — đoạn dưới đây chỉ là chọn NGẪU NHIÊN.\nLý do: " + Http.shorten(e.getMessage(), 300)
                    + "\nAnh vào Cài đặt › Kiểm tra khóa AI, hoặc bấm \"Đổi đoạn khác\" để thử lại.";
            pl.tamTrang = "";
            ui.post(() -> showPlan(pl));
        }
    }

    private void showPlan(AiDirector.Plan pl) {
        plan = pl;
        spinner.setVisibility(View.GONE);
        status.setText("Xong phần chọn. Anh xem lại rồi bấm Tạo video.");
        boolean failed = pl.moTa.startsWith("⚠") || pl.moTa.startsWith("(Chưa có khóa");
        sceneText.setTextColor(failed ? 0xFFFF6B6B : Ui.MUTED);
        if (failed) status.setText("AI không chạy được, xem lý do bên dưới.");
        String scene = "AI thấy: " + pl.moTa;
        if (failed) scene = pl.moTa;
        if (!pl.tamTrang.isEmpty()) scene += "\nCảm xúc: " + pl.tamTrang;
        if (!pl.cachDoc.isEmpty()) scene += "\nCách đọc: " + pl.cachDoc;
        if (!failed) scene += pl.doanIndex >= 0 ? "\nĐoạn lấy từ kho văn." : "\nKhông có đoạn hợp, AI tự viết.";
        sceneText.setText(scene);
        vanEdit.setText(pl.van);
        nguonEdit.setText("Văn mẫu".equals(pl.nguon) ? "" : pl.nguon);
        for (int i = 0; i < music.size(); i++) if (music.get(i).id.equals(pl.musicId)) musicSpin.setSelection(i);
        String g = prefs.voiceGender();
        voiceSpin.setSelection("nu".equals("auto".equals(g) ? pl.gioiTinh : g) ? 1 : 0);
        planCard.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ bước 2: dựng video

    private void makeVideo() {
        String text = vanEdit.getText().toString().trim();
        if (text.isEmpty()) { Toast.makeText(this, "Chưa có đoạn văn", Toast.LENGTH_SHORT).show(); return; }
        String nguon = nguonEdit.getText().toString().trim();
        MusicLib.Track track = music.isEmpty() ? null : music.get(Math.max(0, musicSpin.getSelectedItemPosition()));
        String gender = voiceSpin.getSelectedItemPosition() == 1 ? "nu" : "nam";
        planCard.setVisibility(View.GONE);
        setBusy(true, "Đang tạo giọng đọc…");
        bg.execute(() -> buildAudio(text, nguon, track, gender));
    }

    private void buildAudio(String text, String nguon, MusicLib.Track track, String gender) {
        try {
            File work = new File(getCacheDir(), "lam");
            work.mkdirs();
            Prefs p = new Prefs(this) {
                @Override public String voiceGender() { return gender; }
            };
            String style = plan != null ? plan.cachDoc : "";
            File voiceFile = TtsEngine.speak(this, p, text, gender, style, work, this::log);
            log("Đang chỉnh nhịp đọc…");
            AudioUtil.Pcm voice = AudioUtil.decodeFile(voiceFile, 180);
            double[] bounds = AudioUtil.speechBounds(voice);
            voice = AudioUtil.lengthenPauses(voice, bounds[0], bounds[1], prefs.pausePercent() / 100.0);
            double speed = Math.max(0.5, Math.min(1.5, prefs.speedPercent() / 100.0));
            // Gemini đã được dặn đọc chậm, chỉ làm chậm thêm một nửa mức đã chọn
            if (voiceFile.getName().contains("gemini")) speed = (speed + 1) / 2;
            voice = AudioUtil.changeSpeed(voice, speed);
            bounds = AudioUtil.speechBounds(voice);
            double speech = bounds[1] - bounds[0];
            List<double[]> gaps = new ArrayList<>();
            for (double[] g : AudioUtil.findGaps(voice, bounds[0], bounds[1], 0.15))
                gaps.add(new double[]{g[0] - bounds[0] + LEAD, g[1] - bounds[0] + LEAD});

            double narrEnd = LEAD + speech;
            int copies = (int) Math.ceil((narrEnd + 2.0) / videoSec);
            copies = Math.max(1, Math.min(6, copies));
            double total = copies * videoSec;

            log("Đang lấy nhạc nền…");
            AudioUtil.Pcm m = null;
            musicCredit = "";
            if (track != null) {
                try {
                    if ("jamendo".equals(track.id)) {
                        log("Đang tìm nhạc trên Jamendo (" + (plan != null ? plan.nhacTags : "") + ")…");
                        MusicLib.Online on = MusicLib.jamendo(prefs, plan != null ? plan.nhacTags : "", work);
                        m = AudioUtil.decodeFile(on.file, total + 5);
                        musicCredit = on.credit;
                    } else {
                        m = track.asset != null ? AudioUtil.decodeAsset(this, track.asset, total + 5)
                                : AudioUtil.decodeUri(this, track.uri, total + 5);
                    }
                } catch (Exception e) {
                    log("Không lấy được bản nhạc (" + Http.shorten(e.getMessage(), 100) + "), dùng nhạc có sẵn…");
                    m = AudioUtil.decodeAsset(this, "nhac/binh_yen.m4a", total + 5);
                }
            }
            float vol;
            try { vol = Integer.parseInt(prefs.musicVolume()) / 100f; } catch (Exception e) { vol = 0.35f; }
            AudioUtil.Pcm mix = AudioUtil.mix(voice, bounds[0], bounds[1], LEAD, m, vol, total);
            File wav = new File(work, "tron.wav");
            AudioUtil.writeWav(wav, mix);

            List<Captions.Line> lines = Captions.build(text, LEAD, narrEnd, gaps);
            String src = prefs.showSource() ? nguon : "";
            double sFrom = narrEnd + 0.3, sTo = Math.min(total - 0.2, narrEnd + 3.8);

            File outDir = new File(getFilesDir(), "videos");
            outDir.mkdirs();
            File out = new File(outDir, "QuayCanh_" + System.currentTimeMillis() + ".mp4");
            final int cp = copies;
            ui.post(() -> render(cp, wav, lines, src, sFrom, sTo, out));
        } catch (Exception e) {
            setBusy(false, "Lỗi khi làm âm thanh: " + e.getMessage());
            ui.post(() -> planCard.setVisibility(View.VISIBLE));
        }
    }

    private void render(int copies, File wav, List<Captions.Line> lines, String src, double sFrom, double sTo, File out) {
        status.setText("Đang dựng video…");
        maker = new VideoMaker();
        try {
            maker.start(this, video, copies, wav, lines, src, sFrom, sTo, out, new VideoMaker.Callback() {
                @Override public void onDone(File f) { finished(f); }
                @Override public void onError(String msg) {
                    setBusy(false, "Dựng video lỗi: " + msg);
                    planCard.setVisibility(View.VISIBLE);
                }
            });
        } catch (Exception e) {
            setBusy(false, "Dựng video lỗi: " + e.getMessage());
            planCard.setVisibility(View.VISIBLE);
            return;
        }
        pollProgress();
    }

    private void pollProgress() {
        ui.postDelayed(() -> {
            if (maker == null) return;
            int pr = maker.progress();
            if (resultFile == null && spinner.getVisibility() == View.VISIBLE) {
                if (pr >= 0) status.setText(String.format(Locale.US, "Đang dựng video… %d%%", pr));
                pollProgress();
            }
        }, 500);
    }

    private void finished(File f) {
        resultFile = f;
        maker = null;
        spinner.setVisibility(View.GONE);
        status.setText("Đang lưu vào thư viện ảnh…");
        bg.execute(() -> {
            String where = saveToGallery(f);
            ui.post(() -> {
                status.setText(musicCredit.isEmpty() ? where : where + "\nNhạc: " + musicCredit
                        + "\n(Nhạc Jamendo theo giấy phép CC: khi đăng video nên ghi tên bài và tác giả.)");
                resultCard.setVisibility(View.VISIBLE);
                player.setVideoPath(f.getAbsolutePath());
                player.setOnPreparedListener(mp -> mp.setLooping(true));
                player.start();
            });
        });
    }

    private String saveToGallery(File f) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, f.getName());
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuayCanh");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new Exception("không tạo được file");
                try (OutputStream os = getContentResolver().openOutputStream(u);
                     InputStream is = new FileInputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
                cv.clear();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(u, cv, null, null);
                galleryUri = u;
                return "Xong! Video đã lưu vào Thư viện › Movies/QuayCanh.";
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "QuayCanh");
                dir.mkdirs();
                File dst = new File(dir, f.getName());
                try (InputStream is = new FileInputStream(f); OutputStream os = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)));
                return "Xong! Video đã lưu vào Movies/QuayCanh.";
            }
        } catch (Exception e) {
            return "Xong! (Chưa lưu được vào thư viện: " + e.getMessage() + " — anh bấm Chia sẻ để lưu)";
        }
    }

    private void share() {
        if (resultFile == null) return;
        Uri u = galleryUri != null ? galleryUri
                : FileProvider.getUriForFile(this, "vn.quaycanh.app.files", resultFile);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Chia sẻ video"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (maker != null) maker.cancel();
        bg.shutdownNow();
    }
}
