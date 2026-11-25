package vn.quaycanh.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Giải mã, đổi tần số, trộn giọng đọc + nhạc nền, ghi WAV. Tất cả ở dạng stereo 44100 Hz. */
public final class AudioUtil {
    public static final int SR = 44100;

    private AudioUtil() {}

    /** Âm thanh stereo xen kẽ L,R,L,R... giá trị -1..1, tần số SR. */
    public static final class Pcm {
        public final float[] data;
        Pcm(float[] d) { data = d; }
        public int frames() { return data.length / 2; }
        public double seconds() { return frames() / (double) SR; }
    }

    // ---------------------------------------------------------------- giải mã

    public static Pcm decodeFile(File f, double maxSec) throws IOException {
        Pcm w = tryReadWav(f);
        if (w != null) return w;
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        return decode(ex, maxSec);
    }

    public static Pcm decodeUri(Context c, Uri uri, double maxSec) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(c, uri, null);
        return decode(ex, maxSec);
    }

    public static Pcm decodeAsset(Context c, String path, double maxSec) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try (AssetFileDescriptor fd = c.getAssets().openFd(path)) {
            ex.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        }
        return decode(ex, maxSec);
    }

    private static Pcm decode(MediaExtractor ex, double maxSec) throws IOException {
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new IOException("File không có âm thanh"); }
        ex.selectTrack(track);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        boolean isFloat = false;

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();
        FloatList out = new FloatList(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inEnd = false, outEnd = false;
        long maxFrames = (long) (maxSec * 48000) + 48000;
        try {
            while (!outEnd) {
                if (!inEnd) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEnd = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer bb = ob.slice().order(ByteOrder.nativeOrder());
                        if (isFloat) {
                            java.nio.FloatBuffer fb = bb.asFloatBuffer();
                            while (fb.hasRemaining()) out.add(fb.get());
                        } else {
                            ShortBuffer sb = bb.asShortBuffer();
                            while (sb.hasRemaining()) out.add(sb.get() / 32768f);
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEnd = true;
                    if (out.size / Math.max(1, ch) > maxFrames) outEnd = true;
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
            ex.release();
        }
        return toStereo44(out.toArray(), rate, ch);
    }

    /** Đọc WAV (PCM 16/24/32-bit hoặc float). Trả null nếu không phải WAV. */
    private static Pcm tryReadWav(File f) throws IOException {
        byte[] all;
        try (FileInputStream is = new FileInputStream(f)) { all = Http.readAll(is); }
        if (all.length < 44 || all[0] != 'R' || all[1] != 'I' || all[2] != 'F' || all[3] != 'F') return null;
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, fmtTag = 1, ch = 1, rate = 44100, bits = 16;
        int dataPos = -1, dataLen = 0;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, "US-ASCII");
            int len = b.getInt(pos + 4);
            if (id.equals("fmt ")) {
                fmtTag = b.getShort(pos + 8) & 0xffff;
                ch = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
                if (fmtTag == 0xFFFE && len >= 26) fmtTag = b.getShort(pos + 32) & 0xffff; // extensible
            } else if (id.equals("data")) {
                dataPos = pos + 8;
                dataLen = (len <= 0 || dataPos + len > all.length) ? all.length - dataPos : len;
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataPos < 0) return null;
        int bytesPer = bits / 8;
        int n = dataLen / bytesPer;
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            int p = dataPos + i * bytesPer;
            if (fmtTag == 3 && bits == 32) s[i] = b.getFloat(p);
            else if (bits == 16) s[i] = b.getShort(p) / 32768f;
            else if (bits == 24) {
                int v = (all[p] & 0xff) | ((all[p + 1] & 0xff) << 8) | (all[p + 2] << 16);
                s[i] = v / 8388608f;
            } else if (bits == 32) s[i] = b.getInt(p) / 2147483648f;
            else if (bits == 8) s[i] = ((all[p] & 0xff) - 128) / 128f;
        }
        return toStereo44(s, rate, Math.max(1, ch));
    }

    private static Pcm toStereo44(float[] in, int rate, int ch) {
        int frames = in.length / ch;
        float[] st = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float l = in[i * ch];
            float r = ch > 1 ? in[i * ch + 1] : l;
            st[i * 2] = l;
            st[i * 2 + 1] = r;
        }
        if (rate == SR) return new Pcm(st);
        double ratio = rate / (double) SR;
        int outFrames = (int) (frames / ratio);
        float[] o = new float[outFrames * 2];
        for (int i = 0; i < outFrames; i++) {
            double src = i * ratio;
            int i0 = (int) src;
            int i1 = Math.min(frames - 1, i0 + 1);
            float t = (float) (src - i0);
            o[i * 2] = st[i0 * 2] * (1 - t) + st[i1 * 2] * t;
            o[i * 2 + 1] = st[i0 * 2 + 1] * (1 - t) + st[i1 * 2 + 1] * t;
        }
        return new Pcm(o);
    }

    // ---------------------------------------------------------------- phân tích

    /** Tìm [đầu, cuối] (giây) của phần có tiếng nói, bỏ khoảng lặng hai đầu. */
    public static double[] speechBounds(Pcm p) {
        int win = SR / 50; // 20ms
        int nWin = p.frames() / win;
        double peak = 0;
        double[] rms = new double[Math.max(1, nWin)];
        for (int w = 0; w < nWin; w++) {
            double s = 0;
            for (int i = w * win; i < (w + 1) * win; i++) {
                float v = (p.data[i * 2] + p.data[i * 2 + 1]) * 0.5f;
                s += v * v;
            }
            rms[w] = Math.sqrt(s / win);
            peak = Math.max(peak, rms[w]);
        }
        double th = Math.max(0.004, peak * 0.06);
        int a = 0, b = nWin - 1;
        while (a < nWin && rms[a] < th) a++;
        while (b > a && rms[b] < th) b--;
        if (a >= nWin) return new double[]{0, p.seconds()};
        return new double[]{a * win / (double) SR, Math.min(p.seconds(), (b + 1) * win / (double) SR)};
    }

    // ---------------------------------------------------------------- trộn

    /**
     * Trộn: giọng đọc bắt đầu tại voiceStartSec, nhạc nền lặp lại suốt totalSec,
     * nhạc tự nhỏ đi khi có giọng đọc, có fade đầu/cuối.
     */
    public static Pcm mix(Pcm voice, double voiceTrimStart, double voiceTrimEnd, double voiceStartSec,
                          Pcm music, float musicVol, double totalSec) {
        int total = (int) (totalSec * SR);
        float[] o = new float[total * 2];

        // giọng đọc: chuẩn hoá độ to
        int v0 = (int) (voiceTrimStart * SR), v1 = Math.min(voice.frames(), (int) (voiceTrimEnd * SR));
        float vpeak = 0.0001f;
        for (int i = v0 * 2; i < v1 * 2; i++) vpeak = Math.max(vpeak, Math.abs(voice.data[i]));
        float vg = 0.92f / vpeak;
        int vs = (int) (voiceStartSec * SR);
        int vLen = Math.max(0, v1 - v0);
        for (int i = 0; i < vLen && vs + i < total; i++) {
            o[(vs + i) * 2] += voice.data[(v0 + i) * 2] * vg;
            o[(vs + i) * 2 + 1] += voice.data[(v0 + i) * 2 + 1] * vg;
        }

        // nhạc nền
        if (music != null && music.frames() > SR) {
            float mpeak = 0.0001f;
            for (float v : music.data) mpeak = Math.max(mpeak, Math.abs(v));
            float mg = musicVol / mpeak;
            int mf = music.frames();
            int fadeIn = SR, fadeOut = 2 * SR;
            int duckA = vs - SR / 2, duckB = vs + vLen + SR / 2;
            float duck = 1f;
            for (int i = 0; i < total; i++) {
                int k = i % mf;
                float env = 1f;
                if (i < fadeIn) env = i / (float) fadeIn;
                if (i > total - fadeOut) env = Math.min(env, (total - i) / (float) fadeOut);
                float target = (i >= duckA && i <= duckB) ? 0.55f : 1f;
                duck += (target - duck) * 0.00006f; // chuyển mượt ~0.4s
                float g = mg * env * duck;
                o[i * 2] += music.data[k * 2] * g;
                o[i * 2 + 1] += music.data[k * 2 + 1] * g;
            }
        }
        // chống vỡ tiếng
        for (int i = 0; i < o.length; i++) {
            float x = o[i];
            o[i] = (float) Math.tanh(x * 1.05) ;
        }
        return new Pcm(o);
    }

    public static void writeWav(File f, Pcm p) throws IOException {
        int n = p.data.length;
        int dataLen = n * 2;
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes("US-ASCII")).putInt(36 + dataLen).put("WAVE".getBytes("US-ASCII"));
            h.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 2)
                    .putInt(SR).putInt(SR * 4).putShort((short) 4).putShort((short) 16);
            h.put("data".getBytes("US-ASCII")).putInt(dataLen);
            os.write(h.array());
            byte[] buf = new byte[8192];
            int bi = 0;
            for (float v : p.data) {
                int s = Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
                buf[bi++] = (byte) s;
                buf[bi++] = (byte) (s >> 8);
                if (bi == buf.length) { os.write(buf); bi = 0; }
            }
            os.write(buf, 0, bi);
        }
    }

    /** Mảng float tự giãn. */
    static final class FloatList {
        float[] a;
        int size;
        FloatList(int cap) { a = new float[cap]; }
        void add(float v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[size++] = v;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, size); }
    }
}
