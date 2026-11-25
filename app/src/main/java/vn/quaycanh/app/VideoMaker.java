package vn.quaycanh.app;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Ghép video gốc (bỏ tiếng) + âm thanh đã trộn + phụ đề, xuất ra MP4. Phải gọi trên luồng chính. */
@UnstableApi
public final class VideoMaker {

    public interface Callback {
        void onDone(File out);
        void onError(String msg);
    }

    private Transformer transformer;

    /**
     * @param copies số lần lặp video gốc (khi giọng đọc dài hơn đoạn quay)
     */
    public void start(Context c, File video, int copies, File mixWav, List<Captions.Line> lines,
                      String source, double sourceFrom, double sourceTo, File out, Callback cb) {
        List<EditedMediaItem> vids = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            // mỗi bản sao một lớp phụ đề riêng (lớp vẽ giữ texture GL riêng)
            TextureOverlay ov = new Captions.Overlay(lines, source, sourceFrom, sourceTo);
            List<Effect> videoEffects = new ArrayList<>();
            videoEffects.add(new OverlayEffect(ImmutableList.of(ov)));
            vids.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video)))
                    .setRemoveAudio(true)
                    .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), videoEffects))
                    .build());
        }
        EditedMediaItemSequence videoSeq = new EditedMediaItemSequence(vids);
        EditedMediaItem audio = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(mixWav))).build();
        EditedMediaItemSequence audioSeq = new EditedMediaItemSequence(ImmutableList.of(audio));
        Composition comp = new Composition.Builder(ImmutableList.of(videoSeq, audioSeq)).build();

        transformer = new Transformer.Builder(c)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        cb.onDone(out);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult exportResult,
                                        ExportException exportException) {
                        String m = exportException.getMessage();
                        Throwable cause = exportException.getCause();
                        if (cause != null) m += " / " + cause.getMessage();
                        cb.onError(m + " (mã " + exportException.errorCode + ")");
                    }
                })
                .build();
        transformer.start(comp, out.getAbsolutePath());
    }

    /** Phần trăm tiến độ, -1 nếu chưa biết. */
    public int progress() {
        if (transformer == null) return -1;
        ProgressHolder ph = new ProgressHolder();
        int st = transformer.getProgress(ph);
        return st == Transformer.PROGRESS_STATE_AVAILABLE ? ph.progress : -1;
    }

    public void cancel() {
        if (transformer != null) transformer.cancel();
    }
}
