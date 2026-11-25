package vn.quaycanh.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Vài hàm dựng giao diện bằng code. */
public final class Ui {
    public static final int RED = 0xFFE8174B;
    public static final int BG = 0xFF0E0F13;
    public static final int CARD = 0xFF1B1D24;
    public static final int MUTED = 0xFFA0A4AE;

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static TextView chip(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9));
        t.setBackground(round(0x88000000, dp(c, 20)));
        return t;
    }

    public static TextView button(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        t.setBackground(round(color, dp(c, 12)));
        return t;
    }

    public static TextView label(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Nút quay: vòng trắng, chấm đỏ; khi đang quay chấm đỏ thành ô vuông. */
    public static final class RecordButton extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean recording;
        private float progress;
        private final RectF r = new RectF();

        public RecordButton(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            dot.setColor(RED);
            prog.setStyle(Paint.Style.STROKE);
            prog.setColor(RED);
            prog.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setRecording(boolean rec) { recording = rec; if (!rec) progress = 0; invalidate(); }
        public void setProgress(float p) { progress = p; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float cx = w / 2, cy = h / 2;
            float stroke = s * 0.07f;
            ring.setStrokeWidth(stroke);
            prog.setStrokeWidth(stroke);
            float rad = s / 2 - stroke;
            cv.drawCircle(cx, cy, rad, ring);
            if (progress > 0) {
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                cv.drawArc(r, -90, 360 * progress, false, prog);
            }
            if (recording) {
                float q = s * 0.17f;
                r.set(cx - q, cy - q, cx + q, cy + q);
                cv.drawRoundRect(r, s * 0.05f, s * 0.05f, dot);
            } else {
                cv.drawCircle(cx, cy, rad - stroke * 1.6f, dot);
            }
        }
    }
}
