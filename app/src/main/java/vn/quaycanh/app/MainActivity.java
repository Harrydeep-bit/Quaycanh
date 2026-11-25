package vn.quaycanh.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Màn hình quay: camera toàn màn hình, nút quay, tối đa 60 giây. */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAM = 1, REQ_PICK = 2;
    private static final long MAX_MS = 60_000;

    private PreviewView previewView;
    private Ui.RecordButton recBtn;
    private TextView timer, hint;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File currentFile;
    private boolean back = true;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        // thanh trên
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 12);
        top.setPadding(pad, Ui.dp(this, 16), pad, pad);
        TextView title = Ui.label(this, "Quay Cảnh", 20, 0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setShadowLayer(6, 0, 2, 0xAA000000);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView kho = Ui.chip(this, "Kho văn");
        kho.setOnClickListener(v -> startActivity(new Intent(this, KhoVanActivity.class)));
        TextView set = Ui.chip(this, "Cài đặt");
        set.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.leftMargin = Ui.dp(this, 8);
        top.addView(kho, cp);
        top.addView(set, cp);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // phần dưới
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, 0, 0, Ui.dp(this, 36));
        timer = Ui.label(this, "", 18, 0xFFFFFFFF);
        timer.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        timer.setShadowLayer(6, 0, 2, 0xAA000000);
        bottom.addView(timer);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lib = Ui.chip(this, "Thư viện");
        lib.setOnClickListener(v -> pickVideo());
        recBtn = new Ui.RecordButton(this);
        recBtn.setOnClickListener(v -> toggleRecord());
        TextView flip = Ui.chip(this, "Đổi cam");
        flip.setOnClickListener(v -> {
            if (recording != null) return;
            back = !back;
            startCamera();
        });
        int side = Ui.dp(this, 96);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(side, -2);
        row.addView(lib, sp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84));
        rp.leftMargin = rp.rightMargin = Ui.dp(this, 22);
        row.addView(recBtn, rp);
        row.addView(flip, new LinearLayout.LayoutParams(side, -2));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-2, -2);
        rowLp.topMargin = Ui.dp(this, 10);
        bottom.addView(row, rowLp);

        hint = Ui.label(this, "Hướng camera vào cảnh đẹp rồi bấm quay · tối đa 60 giây", 13, 0xFFEEEEEE);
        hint.setShadowLayer(6, 0, 2, 0xAA000000);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = Ui.dp(this, 12);
        bottom.addView(hint, hp);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else hint.setText("App cần quyền camera để quay. Vào Cài đặt điện thoại để cấp quyền.");
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                provider.unbindAll();
                provider.bindToLifecycle(this,
                        back ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview, videoCapture);
            } catch (Exception e) {
                hint.setText("Không mở được camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleRecord() {
        if (videoCapture == null) return;
        if (recording != null) {
            recording.stop();
            return;
        }
        File dir = new File(getCacheDir(), "quay");
        dir.mkdirs();
        currentFile = new File(dir, "canh_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(currentFile).setDurationLimitMillis(MAX_MS).build();
        recording = videoCapture.getOutput().prepareRecording(this, opts)
                .start(ContextCompat.getMainExecutor(this), this::onRecordEvent);
        recBtn.setRecording(true);
        hint.setText("Đang quay… bấm lần nữa để dừng");
    }

    private void onRecordEvent(VideoRecordEvent e) {
        if (e instanceof VideoRecordEvent.Status) {
            long ms = e.getRecordingStats().getRecordedDurationNanos() / 1_000_000;
            timer.setText(String.format(java.util.Locale.US, "● %02d:%02d", ms / 60000, (ms / 1000) % 60));
            recBtn.setProgress(ms / (float) MAX_MS);
        } else if (e instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) e;
            recording = null;
            recBtn.setRecording(false);
            timer.setText("");
            hint.setText("Hướng camera vào cảnh đẹp rồi bấm quay · tối đa 60 giây");
            long ms = fin.getRecordingStats().getRecordedDurationNanos() / 1_000_000;
            boolean ok = !fin.hasError() || fin.getError() == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED;
            if (!ok || currentFile == null || !currentFile.exists()) {
                Toast.makeText(this, "Quay lỗi (mã " + fin.getError() + ")", Toast.LENGTH_LONG).show();
                return;
            }
            if (ms < 3000) {
                Toast.makeText(this, "Quay ít nhất 3 giây nhé anh", Toast.LENGTH_SHORT).show();
                return;
            }
            openProcess(currentFile);
        }
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        startActivityForResult(Intent.createChooser(i, "Chọn video"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            hint.setText("Đang chép video…");
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), "quay");
                    dir.mkdirs();
                    File f = new File(dir, "chon_" + System.currentTimeMillis() + ".mp4");
                    try (InputStream is = getContentResolver().openInputStream(u);
                         OutputStream os = new FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    ui.post(() -> openProcess(f));
                } catch (Exception ex) {
                    ui.post(() -> hint.setText("Không đọc được video: " + ex.getMessage()));
                }
            }).start();
        }
    }

    private void openProcess(File f) {
        Intent i = new Intent(this, ProcessActivity.class);
        i.putExtra("video", f.getAbsolutePath());
        startActivity(i);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording != null) recording.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hint != null && recording == null)
            hint.setText("Hướng camera vào cảnh đẹp rồi bấm quay · tối đa 60 giây");
        View v = getWindow().getDecorView();
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
