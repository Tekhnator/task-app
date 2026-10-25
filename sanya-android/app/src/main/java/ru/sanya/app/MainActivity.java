package ru.sanya.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;
import java.util.Locale;

/**
 * Оболочка для страницы assets/index.html.
 * Распознавание речи (SpeechRecognizer) и озвучка (TextToSpeech) — родные функции Android,
 * страница вызывает их через мост window.Android, ответы приходят в window.onNative(событие, данные).
 */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final Locale RU = new Locale("ru", "RU");
    private WebView web;
    private TextToSpeech tts;
    private SpeechRecognizer sr;
    private boolean ttsReady = false;
    private List<String> engineQueue = null;   // какие синтезаторы ещё попробовать
    private String engineName = "";
    private boolean ttsFailed = false;

    /* ===== GigaChat (Сбер): токен на 30 минут, потоковые ответы ===== */
    private static final String GIGA_AUTH = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final String[] GIGA_BASES = { "https://gigachat.devices.sberbank.ru/api/v1", "https://api.giga.chat/v1" };
    private String gigaTok = null, gigaTokKey = "";
    private long gigaExp = 0;
    private final ConcurrentHashMap<String, HttpURLConnection> gigaConns = new ConcurrentHashMap<>();
    private final java.util.Set<String> gigaAborted = ConcurrentHashMap.newKeySet();

    static class GigaErr extends Exception { final int status; GigaErr(int s, String m) { super(m); status = s; } }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(); BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String l; while ((l = r.readLine()) != null) sb.append(l).append('\n'); return sb.toString();
    }

    private static String errText(String body) {
        try { JSONObject o = new JSONObject(body); String m = o.optString("message", o.optString("error_description", o.optString("error", ""))); if (!m.isEmpty()) return m; } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private synchronized String gigaToken(String key, boolean force) throws Exception {
        if (!force && gigaTok != null && key.equals(gigaTokKey) && System.currentTimeMillis() < gigaExp - 60000) return gigaTok;
        HttpURLConnection c = (HttpURLConnection) new URL(GIGA_AUTH).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Basic " + key.trim());
        c.setRequestProperty("RqUID", UUID.randomUUID().toString());
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write("scope=GIGACHAT_API_PERS".getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code != 200) throw new GigaErr(code, code == 401 ? "неверный ключ авторизации" : errText(body));
        JSONObject j = new JSONObject(body);
        gigaTok = j.has("access_token") ? j.getString("access_token") : j.getString("tok");
        gigaExp = j.has("expires_at") ? j.getLong("expires_at") : j.optLong("exp", System.currentTimeMillis() + 25 * 60000);
        if (gigaExp < 100000000000L) gigaExp *= 1000;   // на всякий случай: секунды → миллисекунды
        gigaTokKey = key;
        return gigaTok;
    }

    private void gigaRun(String id, String key, String body) {
        try {
            String tok = gigaToken(key, false);
            Exception last = null;
            for (String base : GIGA_BASES) {
                try { gigaStreamOnce(id, base, tok, body, true); return; }
                catch (GigaErr e) { throw e; }
                catch (Exception e) { last = e; if (gigaAborted.contains(id)) return; }   // сеть/сертификат — пробуем другой адрес
            }
            if (last != null) throw last;
        } catch (GigaErr e) {
            if (!gigaAborted.remove(id)) emit("gerr", gj(id, e.status, e.getMessage()));
        } catch (Exception e) {
            if (!gigaAborted.remove(id)) emit("gerr", gj(id, 0, e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally { gigaConns.remove(id); }
    }

    private void gigaStreamOnce(String id, String base, String tok, String body, boolean retry401) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        gigaConns.put(id, c);
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(40000);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        if (code == 401 && retry401) { gigaStreamOnce(id, base, gigaToken(gigaTokKey, true), body, false); return; }
        if (code != 200) throw new GigaErr(code, errText(readAll(c.getErrorStream())));
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            if (gigaAborted.contains(id)) { c.disconnect(); gigaAborted.remove(id); return; }
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String v = line.substring(5).trim();
            if (v.equals("[DONE]")) break;
            try {
                JSONObject ch = new JSONObject(v).getJSONArray("choices").getJSONObject(0);
                JSONObject d = ch.optJSONObject("delta");
                String t = d != null ? d.optString("content", "") : "";
                if (!t.isEmpty()) emit("gdelta", gj(id, 0, null, t));
            } catch (Exception ignored) {}
        }
        emit("gdone", gj(id, 0, null));
    }

    private static String gj(String id, int status, String msg) { return gj(id, status, msg, null); }
    private static String gj(String id, int status, String msg, String t) {
        try { JSONObject o = new JSONObject(); o.put("id", id); if (status != 0) o.put("status", status); if (msg != null) o.put("msg", msg); if (t != null) o.put("t", t); return o.toString(); }
        catch (Exception e) { return "{\"id\":\"" + id + "\"}"; }
    }
    // офлайн-распознавание Vosk (для планшетов без сервисов Google, например Huawei)
    private Model voskModel;
    private SpeechService vosk;
    private boolean voskLoading = false;
    private String voskError = null;
    private boolean voskGotResult = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PermissionRequest pendingCam;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);   // чтобы страница могла звать api.anthropic.com
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient() {
            // камера для сканирования QR-кода с ключом
            @Override public void onPermissionRequest(final PermissionRequest req) {
                ui.post(() -> {
                    boolean wantsCam = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCam = true;
                    if (!wantsCam) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else { pendingCam = req; requestPermissions(new String[]{Manifest.permission.CAMERA}, 2); }
                });
            }
        });
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideBars();

        tts = new TextToSpeech(this, this);
        loadVosk();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private boolean hasRussian() {
        try { return tts.isLanguageAvailable(RU) >= TextToSpeech.LANG_AVAILABLE; } catch (Exception e) { return false; }
    }

    /** Ищем синтезатор, в котором есть русский голос: сначала системный, потом остальные (RHVoice первым). */
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && hasRussian()) {
            tts.setLanguage(RU);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { emit("spoken", id); }
                @Override public void onError(String id) { emit("spoken", id); }
                @Override public void onStop(String id, boolean interrupted) {}
            });
            ttsReady = true;
            try { engineName = tts.getDefaultEngine(); } catch (Exception ignored) {}
            emit("ttsready", engineName);
            return;
        }
        if (engineQueue == null) {
            engineQueue = new ArrayList<>();
            String cur = "";
            try { cur = tts.getDefaultEngine(); } catch (Exception ignored) {}
            try {
                for (TextToSpeech.EngineInfo e : tts.getEngines()) {
                    if (e.name.equals(cur)) continue;
                    if (e.name.contains("rhvoice")) engineQueue.add(0, e.name); else engineQueue.add(e.name);
                }
            } catch (Exception ignored) {}
        }
        if (!engineQueue.isEmpty()) {
            final String next = engineQueue.remove(0);
            try { tts.shutdown(); } catch (Exception ignored) {}
            ui.post(() -> { engineName = next; tts = new TextToSpeech(MainActivity.this, MainActivity.this, next); });
            return;
        }
        ttsFailed = true;
        emit("ttsmissing", "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // вернулись после установки голоса — пробуем снова
        if (ttsFailed && !ttsReady && tts != null) {
            ttsFailed = false; engineQueue = null;
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = new TextToSpeech(this, this);
        }
    }

    private void loadVosk() {
        if (voskModel != null || voskLoading) return;
        voskLoading = true;
        StorageService.unpack(this, "model-ru", "model",
                m -> { voskModel = m; voskLoading = false; emit("asrready", "vosk"); },
                e -> { voskError = e.getMessage(); voskLoading = false; });
    }

    private boolean useGoogle(String mode) {
        if ("vosk".equals(mode)) return false;
        boolean avail = SpeechRecognizer.isRecognitionAvailable(this);
        if ("google".equals(mode)) return avail;
        return avail && voskModel == null ? true : avail && !isHuawei();
    }

    private boolean isHuawei() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("huawei") || m.contains("honor");
    }

    private void stopVosk() {
        if (vosk != null) { try { vosk.stop(); vosk.shutdown(); } catch (Exception ignored) {} vosk = null; }
    }

    private void startVosk() {
        if (voskModel == null) {
            emit("error", voskLoading ? "loading" : "no-recognizer"); emit("end", ""); return;
        }
        try {
            stopVosk();
            Recognizer rec = new Recognizer(voskModel, 16000.0f);
            rec.setMaxAlternatives(5);
            voskGotResult = false;
            vosk = new SpeechService(rec, 16000.0f);
            vosk.startListening(new org.vosk.android.RecognitionListener() {
                @Override public void onPartialResult(String hyp) {
                    try { String t = new JSONObject(hyp).optString("partial", ""); if (!t.trim().isEmpty()) emit("partial", t); } catch (Exception ignored) {}
                }
                @Override public void onResult(String hyp) { handleVosk(hyp); }
                @Override public void onFinalResult(String hyp) { }
                @Override public void onError(Exception e) { stopVosk(); emit("error", "other-vosk"); emit("end", ""); }
                @Override public void onTimeout() { if (!voskGotResult) { stopVosk(); emit("error", "no-speech"); emit("end", ""); } }
            }, 8000);
        } catch (Exception e) {
            emit("error", "other-vosk"); emit("end", "");
        }
    }

    // Vosk выдаёт результат на каждой паузе; берём первую непустую фразу
    private void handleVosk(String hyp) {
        if (voskGotResult) return;
        try {
            JSONObject o = new JSONObject(hyp);
            JSONArray out = new JSONArray();
            JSONArray alts = o.optJSONArray("alternatives");
            if (alts != null) {
                for (int i = 0; i < alts.length(); i++) {
                    JSONObject a = alts.getJSONObject(i);
                    String t = a.optString("text", "").trim();
                    if (t.isEmpty()) continue;
                    JSONObject r = new JSONObject(); r.put("t", t); r.put("c", 0); out.put(r);
                }
            } else {
                String t = o.optString("text", "").trim();
                if (!t.isEmpty()) { JSONObject r = new JSONObject(); r.put("t", t); r.put("c", 0); out.put(r); }
            }
            if (out.length() == 0) return;          // шум — слушаем дальше
            voskGotResult = true;
            stopVosk();
            emit("final", out.toString());
            emit("end", "");
        } catch (Exception ignored) {}
    }

    private void emit(String ev, String data) {
        final String js = "window.onNative&&window.onNative(" + JSONObject.quote(ev) + "," + JSONObject.quote(data) + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) {}
        @Override public void onBeginningOfSpeech() {}
        private long lastRms = 0;
        @Override public void onRmsChanged(float v) {
            long now = System.currentTimeMillis();
            if (now - lastRms > 90) { lastRms = now; emit("rms", String.valueOf(v)); }   // громкость голоса для подсветки
        }
        @Override public void onBufferReceived(byte[] buf) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int t, Bundle p) {}

        @Override public void onPartialResults(Bundle p) {
            ArrayList<String> m = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (m != null && !m.isEmpty() && !m.get(0).trim().isEmpty()) emit("partial", m.get(0));
        }

        @Override public void onResults(Bundle p) {
            ArrayList<String> m = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            float[] c = p.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
            JSONArray arr = new JSONArray();
            if (m != null) {
                for (int i = 0; i < m.size(); i++) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("t", m.get(i));
                        o.put("c", c != null && i < c.length ? c[i] : 0);
                        arr.put(o);
                    } catch (Exception ignored) {}
                }
            }
            if (arr.length() > 0) emit("final", arr.toString());
            emit("end", "");
        }

        @Override public void onError(int code) {
            String e;
            switch (code) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: e = "no-speech"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: e = "not-allowed"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: e = "network"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: e = "busy"; break;
                default: e = "other-" + code;
            }
            emit("error", e);
            emit("end", "");
        }
    };

    private class Bridge {
        @JavascriptInterface
        public void listen(final String lang) { listen2(lang, "auto"); }

        @JavascriptInterface
        public String asrInfo(String mode) {
            boolean g = SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
            String cur = useGoogle(mode) ? "Google" : "офлайн Vosk";
            return "Сейчас: " + cur + ". Google на устройстве: " + (g ? "есть" : "нет") + ". Vosk: "
                    + (voskModel != null ? "готов" : voskLoading ? "загружается…" : "ошибка " + voskError);
        }

        @JavascriptInterface
        public void listen2(final String lang, final String mode) {
            ui.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    emit("error", "not-allowed"); emit("end", ""); return;
                }
                if (!useGoogle(mode)) { startVosk(); return; }
                // новый распознаватель на каждую фразу — так меньше ошибок «занято»
                if (sr != null) { sr.destroy(); sr = null; }
                sr = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                sr.setRecognitionListener(listener);
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                // малыши делают паузы посреди фразы — даём чуть больше времени
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                sr.startListening(i);
            });
        }

        @JavascriptInterface
        public void stopListen() {
            ui.post(() -> { if (sr != null) { sr.cancel(); sr.destroy(); sr = null; } voskGotResult = true; stopVosk(); });
        }

        @JavascriptInterface
        public void speak(String text, String id, float rate, float pitch, String voiceName) {
            if (!ttsReady) { emit("spoken", id); return; }
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            if (voiceName != null && !voiceName.isEmpty() && Build.VERSION.SDK_INT >= 21) {
                try {
                    for (Voice v : tts.getVoices()) if (v.getName().equals(voiceName)) { tts.setVoice(v); break; }
                } catch (Exception ignored) {}
            }
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
        }

        @JavascriptInterface
        public void giga(final String id, final String key, final String body) {
            gigaAborted.remove(id);
            new Thread(() -> gigaRun(id, key, body), "giga-" + id).start();
        }

        @JavascriptInterface
        public void gigaAbort(String id) {
            gigaAborted.add(id);
            HttpURLConnection c = gigaConns.remove(id);
            if (c != null) new Thread(c::disconnect).start();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            // ссылки открываем во внешнем браузере (например, загрузка RHVoice)
            if (url == null || !url.startsWith("https://")) return;
            ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (Exception ignored) {} });
        }

        @JavascriptInterface
        public void stopSpeak() { if (tts != null) tts.stop(); }

        @JavascriptInterface
        public String voices() {
            JSONArray arr = new JSONArray();
            if (!ttsReady) return arr.toString();
            try {
                int n = 0;
                for (Voice v : tts.getVoices()) {
                    if (!"rus".equals(v.getLocale().getISO3Language()) && !"ru".equals(v.getLocale().getLanguage())) continue;
                    JSONObject o = new JSONObject();
                    n++;
                    o.put("voiceURI", v.getName());
                    o.put("name", "Голос " + n + " — " + v.getName());
                    o.put("localService", !v.isNetworkConnectionRequired());
                    o.put("lang", "ru-RU");
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 2 && pendingCam != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pendingCam.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingCam.deny();
            pendingCam = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("typeof stopAll==='function'&&stopAll()", null);
        if (tts != null) tts.stop();
        if (sr != null) { sr.cancel(); sr.destroy(); sr = null; }
        voskGotResult = true; stopVosk();
    }

    @Override
    public void onBackPressed() {
        // кнопка «Назад» закрывает настройки, а на главном экране ничего не делает — чтобы малыш случайно не вышел
        if (web != null) web.evaluateJavascript("(function(){var p=document.getElementById('panel');if(p&&p.classList.contains('on')){document.getElementById('pclose').click();}})()", null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (sr != null) sr.destroy();
        stopVosk();
        if (voskModel != null) voskModel.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
