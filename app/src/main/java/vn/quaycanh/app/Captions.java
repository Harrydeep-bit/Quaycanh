package vn.quaycanh.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Phụ đề chạy theo từng chữ, chữ đang đọc được tô nền đỏ (giống clip mẫu). */
public final class Captions {

    public static final class Word {
        public final String text;
        public double start, end; // giây, tính trên video thành phẩm
        Word(String t) { text = t; }
    }

    public static final class Line {
        public final List<Word> words = new ArrayList<>();
        public double start, end;
    }

    private Captions() {}

    /**
     * Chia văn bản thành từng chữ và ước lượng thời điểm đọc mỗi chữ:
     * tiếng Việt mỗi chữ là một âm tiết nên thời gian gần đều nhau, thêm khoảng nghỉ ở dấu câu.
     */
    public static List<Line> build(String text, double speechStart, double speechEnd) {
        String[] raw = text.replace('\n', ' ').trim().split("\\s+");
        List<Word> words = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (String r : raw) {
            if (r.isEmpty()) continue;
            Word w = new Word(r);
            words.add(w);
            double wt = 1.0;
            char last = r.charAt(r.length() - 1);
            double pause = 0;
            if (last == ',' || last == ';' || last == ':') pause = 1.3;
            else if (last == '.' || last == '!' || last == '?' || last == '…') pause = 2.4;
            weights.add(wt);
            weights.add(pause);
            total += wt + pause;
        }
        if (words.isEmpty()) return new ArrayList<>();
        // khoảng nghỉ sau chữ cuối không tính
        total -= weights.get(weights.size() - 1);
        double unit = (speechEnd - speechStart) / Math.max(1e-6, total);
        double t = speechStart;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            w.start = t;
            t += weights.get(i * 2) * unit;
            w.end = t;
            if (i < words.size() - 1) t += weights.get(i * 2 + 1) * unit;
        }

        // gom thành từng dòng ngắn (tối đa 4 chữ / 22 ký tự), ngắt ở dấu câu
        List<Line> lines = new ArrayList<>();
        Line cur = new Line();
        int chars = 0;
        for (Word w : words) {
            if (!cur.words.isEmpty() && (cur.words.size() >= 4 || chars + w.text.length() > 22)) {
                lines.add(cur);
                cur = new Line();
                chars = 0;
            }
            cur.words.add(w);
            chars += w.text.length() + 1;
            char last = w.text.charAt(w.text.length() - 1);
            if (last == ',' || last == '.' || last == '!' || last == '?' || last == ';' || last == '…' || last == ':') {
                lines.add(cur);
                cur = new Line();
                chars = 0;
            }
        }
        if (!cur.words.isEmpty()) lines.add(cur);
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            l.start = l.words.get(0).start - 0.08;
            double nextStart = i + 1 < lines.size() ? lines.get(i + 1).words.get(0).start - 0.08 : Double.MAX_VALUE;
            l.end = Math.min(l.words.get(l.words.size() - 1).end + 0.7, nextStart);
        }
        return lines;
    }

    /** Lớp vẽ phụ đề lên từng khung hình video. */
    @UnstableApi
    public static final class Overlay extends CanvasOverlay {
        private final List<Line> lines;
        private final String source;
        private final double sourceFrom, sourceTo;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint src = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public Overlay(List<Line> lines, String source, double sourceFrom, double sourceTo) {
            super(/* useInputFrameAspectRatio= */ true);
            this.lines = lines;
            this.source = source;
            this.sourceFrom = sourceFrom;
            this.sourceTo = sourceTo;
            Typeface tf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            text.setTypeface(tf);
            text.setColor(Color.WHITE);
            text.setShadowLayer(8f, 0f, 3f, 0xCC000000);
            stroke.setTypeface(tf);
            stroke.setColor(0xFF111111);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            hl.setColor(0xFFE8174B);
            src.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            src.setColor(0xF2FFFFFF);
            src.setShadowLayer(6f, 0f, 2f, 0xCC000000);
            src.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void onDraw(Canvas canvas, long presentationTimeUs) {
            double t = presentationTimeUs / 1_000_000.0;
            int W = canvas.getWidth(), H = canvas.getHeight();
            float base = Math.min(W, H);
            Line line = null;
            for (Line l : lines) if (t >= l.start && t < l.end) { line = l; break; }
            if (line != null) {
                float size = base * 0.072f;
                text.setTextSize(size);
                stroke.setTextSize(size);
                stroke.setStrokeWidth(size * 0.12f);
                float space = text.measureText(" ");
                float total = 0;
                String[] up = new String[line.words.size()];
                float[] widths = new float[up.length];
                for (int i = 0; i < up.length; i++) {
                    up[i] = line.words.get(i).text.toUpperCase(new Locale("vi"));
                    widths[i] = text.measureText(up[i]);
                    total += widths[i] + (i > 0 ? space : 0);
                }
                float scale = 1f;
                if (total > W * 0.9f) {
                    scale = W * 0.9f / total;
                    text.setTextSize(size * scale);
                    stroke.setTextSize(size * scale);
                    stroke.setStrokeWidth(size * scale * 0.12f);
                    space *= scale;
                    total *= scale;
                    for (int i = 0; i < widths.length; i++) widths[i] *= scale;
                }
                float x = (W - total) / 2f;
                float y = H * 0.70f;
                Paint.FontMetrics fm = text.getFontMetrics();
                for (int i = 0; i < up.length; i++) {
                    Word w = line.words.get(i);
                    if (t >= w.start && t < w.end + 0.05) {
                        float padX = size * scale * 0.12f, padY = size * scale * 0.06f;
                        rect.set(x - padX, y + fm.ascent * 0.92f - padY, x + widths[i] + padX, y + fm.descent * 0.55f + padY);
                        canvas.drawRoundRect(rect, size * 0.14f, size * 0.14f, hl);
                    }
                    canvas.drawText(up[i], x, y, stroke);
                    canvas.drawText(up[i], x, y, text);
                    x += widths[i] + space;
                }
            }
            if (source != null && !source.isEmpty() && t >= sourceFrom && t < sourceTo) {
                double a = Math.min(1, Math.min((t - sourceFrom) / 0.6, (sourceTo - t) / 0.6));
                src.setAlpha((int) (240 * Math.max(0, a)));
                src.setTextSize(base * 0.042f);
                canvas.drawText("— " + source + " —", W / 2f, H * 0.82f, src);
            }
        }
    }
}
