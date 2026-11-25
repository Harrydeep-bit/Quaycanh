package vn.quaycanh.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Các cài đặt của app, lưu trong SharedPreferences. */
public class Prefs {
    public static final String AI_GEMINI = "gemini";
    public static final String AI_CLAUDE = "claude";

    public static final String TTS_VIENEU = "vieneu";
    public static final String TTS_OMNI = "omni";
    public static final String TTS_ANDROID = "android";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("quaycanh", Context.MODE_PRIVATE);
    }

    public String get(String k, String def) { return sp.getString(k, def); }
    public boolean getBool(String k, boolean def) { return sp.getBoolean(k, def); }
    public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    public void putBool(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }

    public String aiProvider() { return get("ai_provider", AI_GEMINI); }
    public String geminiKey() { return get("gemini_key", "").trim(); }
    public String geminiModel() { return get("gemini_model", "gemini-flash-latest").trim(); }
    public String claudeKey() { return get("claude_key", "").trim(); }
    public String claudeModel() { return get("claude_model", "claude-haiku-5-5").trim(); }

    public String ttsEngine() { return get("tts_engine", TTS_VIENEU); }
    public String hfToken() { return get("hf_token", "").trim(); }
    /** Giọng VieNeu: "auto" = để AI chọn nam/nữ. */
    public String vieneuVoiceMale() { return get("vieneu_male", "Thiện Minh"); }
    public String vieneuVoiceFemale() { return get("vieneu_female", "Ngọc Huyền"); }
    public String vieneuHost() { return get("vieneu_host", "https://pnnbao-ump-vieneu-tts-v3-turbo.hf.space").trim(); }
    public String omniHost() { return get("omni_host", "https://k2-fsa-omnivoice.hf.space").trim(); }
    /** nam / nu / auto */
    public String voiceGender() { return get("voice_gender", "auto"); }

    public String musicFolder() { return get("music_folder", ""); }
    public boolean aiWriteOwn() { return getBool("ai_write_own", true); }
    public boolean showSource() { return getBool("show_source", true); }
    public String musicVolume() { return get("music_volume", "35"); }
}
