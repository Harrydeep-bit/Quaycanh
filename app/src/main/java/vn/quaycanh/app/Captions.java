package vn.quaycanh.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.media3.common.util.UnstableApi;
import android.graphics.Bitmap;

import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;

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
        return build(text, speechStart, speechEnd, null);
    }

    private static boolean isBreak(String w) {
        char last = w.charAt(w.length() - 1);
        return last == ',' || last == ';' || last == ':' || last == '.' || last == '!' || last == '?' || last == '…';
    }

    /** Chia đều thời gian [a,b] cho các chữ (theo trọng số, có nghỉ ở dấu câu). */
    private static void spread(List<Word> words, double a, double b) {
        if (words.isEmpty()) return;
        double total = 0;
        double[] wt = new double[words.size()], pause = new double[words.size()];
        for (int i = 0; i < words.size(); i++) {
            String r = words.get(i).text;
            wt[i] = 1.0;
            char last = r.charAt(r.length() - 1);
            if (i < words.size() - 1) {
                if (last == ',' || last == ';' || last == ':') pause[i] = 1.3;
                else if (last == '.' || last == '!' || last == '?' || last == '…') pause[i] = 2.4;
            }
            total += wt[i] + pause[i];
        }
        double unit = (b - a) / Math.max(1e-6, total);
        double t = a;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            w.start = t;
            t += wt[i] * unit;
            w.end = t;
            t += pause[i] * unit;
        }
    }

    /**
     * Ước lượng thời điểm đọc mỗi chữ. Nếu có danh sách khoảng lặng (gaps) đo được từ giọng đọc,
     * dùng chúng để khớp chỗ ngắt câu cho chính xác.
     */
    public static List<Line> build(String text, double speechStart, double speechEnd, List<double[]> gaps) {
        String[] raw = text.replace('\n', ' ').trim().split("\\s+");
        List<Word> words = new ArrayList<>();
        for (String r : raw) if (!r.isEmpty()) words.add(new Word(r));
        if (words.isEmpty()) return new ArrayList<>();

        // các cụm từ, ngắt ở dấu câu
        List<List<Word>> phrases = new ArrayList<>();
        List<Word> cur0 = new ArrayList<>();
        for (Word w : words) {
            cur0.add(w);
            if (isBreak(w.text)) { phrases.add(cur0); cur0 = new ArrayList<>(); }
        }
        if (!cur0.isEmpty()) phrases.add(cur0);
        int need = phrases.size() - 1;
        if (gaps != null && need > 0 && gaps.size() >= need) {
            // lấy các khoảng lặng dài nhất làm chỗ ngắt cụm
            List<double[]> g = new ArrayList<>(gaps);
            g.sort((x, y) -> Double.compare(y[1] - y[0], x[1] - x[0]));
            g = new ArrayList<>(g.subList(0, need));
            g.sort((x, y) -> Double.compare(x[0], y[0]));
            double a = speechStart;
            for (int i = 0; i < phrases.size(); i++) {
                double b = i < need ? g.get(i)[0] : speechEnd;
                spread(phrases.get(i), a, Math.max(a + 0.2, b));
                if (i < need) a = g.get(i)[1];
            }
        } else {
            spread(words, speechStart, speechEnd);
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
    public static final class Overlay extends BitmapOverlay {
        private final List<Line> lines;
        private final String source;
        private final double sourceFrom, sourceTo;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint src = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public Overlay(List<Line> lines, String source, double sourceFrom, double sourceTo) {
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

        // hai bitmap luân phiên để thư viện luôn nạp lại hình mới mỗi khung
        private final Bitmap[] bufs = new Bitmap[2];
        private final Canvas[] canvases = new Canvas[2];
        private int which;

        @Override
        public void configure(Size videoSize) {
            super.configure(videoSize);
            int w = Math.max(2, videoSize.getWidth()), h = Math.max(2, videoSize.getHeight());
            if (bufs[0] == null || bufs[0].getWidth() != w || bufs[0].getHeight() != h) {
                for (int i = 0; i < 2; i++) {
                    bufs[i] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    canvases[i] = new Canvas(bufs[i]);
                }
            }
        }

        @Override
        public Bitmap getBitmap(long presentationTimeUs) {
            if (bufs[0] == null) configure(new Size(1080, 1920));
            which ^= 1;
            bufs[which].eraseColor(Color.TRANSPARENT);
            onDraw(canvases[which], presentationTimeUs);
            return bufs[which];
        }

        private void onDraw(Canvas canvas, long presentationTimeUs) {
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
