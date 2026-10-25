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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
    private final Handler ui = new Handler(Looper.getMainLooper());

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
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideBars();

        tts = new TextToSpeech(this, this);
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

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) { emit("ttsmissing", ""); return; }
        int r = tts.setLanguage(RU);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) emit("ttsmissing", "");
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { emit("spoken", id); }
            @Override public void onError(String id) { emit("spoken", id); }
            @Override public void onStop(String id, boolean interrupted) {}
        });
        ttsReady = true;
        emit("ttsready", "");
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
        public void listen(final String lang) {
            ui.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    emit("error", "not-allowed"); emit("end", ""); return;
                }
                if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                    emit("error", "no-recognizer"); emit("end", ""); return;
                }
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
            ui.post(() -> { if (sr != null) { sr.cancel(); sr.destroy(); sr = null; } });
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
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("typeof stopAll==='function'&&stopAll()", null);
        if (tts != null) tts.stop();
        if (sr != null) { sr.cancel(); sr.destroy(); sr = null; }
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
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
