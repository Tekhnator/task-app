const { app, BrowserWindow, Menu, ipcMain, net, shell, safeStorage } = require('electron');
const { execFile } = require('child_process');
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');

/* ===================== Сеть для страницы =====================
   Запросы (погода, праздники, календари .ics, Google API) выполняются здесь,
   поэтому их не режут ограничения страниц, открытых как файл. */
async function doRequest({ url, method = 'GET', headers = {}, body, timeoutMs = 15000 }) {
    let u;
    try { u = new URL(String(url).replace(/^webcal:\/\//i, 'https://')); } catch { return { error: 'Неверный адрес' }; }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return { error: 'Поддерживаются только http(s)-адреса' };
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), Math.min(Math.max(timeoutMs || 15000, 1000), 30000));
    try {
        const r = await net.fetch(u.toString(), {
            method, signal: ctrl.signal, body: body == null ? undefined : body,
            headers: Object.assign({ 'Accept': '*/*' }, headers && typeof headers === 'object' ? headers : {}),
        });
        return { status: r.status, body: await r.text() };
    } catch (err) {
        return { error: err && err.name === 'AbortError' ? 'Сервер не ответил вовремя' : String((err && err.message) || err) };
    } finally {
        clearTimeout(timer);
    }
}
ipcMain.handle('net:get', (_e, url, timeoutMs, headers) => doRequest({ url, timeoutMs, headers }));
ipcMain.handle('net:request', (_e, opts) => doRequest(opts || {}));

/* ===================== Вход в Google для настольной программы =====================
   Вход открывается в обычном браузере (OAuth с PKCE и возвратом на 127.0.0.1).
   Ключ продления хранится зашифрованным средствами Windows,
   поэтому входить нужно один раз, а не каждый час. */
const SCOPE = 'https://www.googleapis.com/auth/calendar.events';
const authFile = () => path.join(app.getPath('userData'), 'google-auth.bin');
let accessCache = { token: null, exp: 0 };

function saveAuth(obj) {
    const json = JSON.stringify(obj);
    const data = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(json) : Buffer.from(json, 'utf8');
    fs.writeFileSync(authFile(), data);
}
function loadAuth() {
    try {
        const buf = fs.readFileSync(authFile());
        const json = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8');
        return JSON.parse(json);
    } catch { return null; }
}
const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function tokenRequest(params) {
    const r = await doRequest({
        url: 'https://oauth2.googleapis.com/token', method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(params).toString(),
    });
    if (r.error) throw new Error(r.error);
    let j = {}; try { j = JSON.parse(r.body); } catch {}
    if (r.status !== 200) throw new Error(j.error_description || j.error || ('HTTP ' + r.status));
    return j;
}

// Ждём, пока браузер вернёт код входа на http://127.0.0.1:<порт>
function waitForCode(clientId, challenge, state) {
    return new Promise((resolve) => {
        let port = 0, done = false;
        const finish = (val) => { if (done) return; done = true; clearTimeout(timeout); try { server.close(); } catch {} resolve(val); };
        const server = http.createServer((req, res) => {
            const u = new URL(req.url, 'http://127.0.0.1');
            if (u.pathname !== '/') { res.writeHead(404); res.end(); return; }
            const code = u.searchParams.get('code'), ok = !!code && u.searchParams.get('state') === state;
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(`<!doctype html><meta charset="utf-8"><title>Задачи</title><body style="font-family:system-ui,sans-serif;background:#05353d;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center"><h2>${ok ? '✓ Готово' : 'Вход не выполнен'}</h2><p>${ok ? 'Закройте эту вкладку и вернитесь в программу «Задачи».' : 'Вернитесь в программу и попробуйте ещё раз.'}</p></div></body>`);
            finish(ok ? { code, redirect: `http://127.0.0.1:${port}` } : { error: u.searchParams.get('error') || 'Вход отменён' });
        });
        const timeout = setTimeout(() => finish({ error: 'Время ожидания входа истекло (5 минут)' }), 5 * 60 * 1000);
        server.on('error', (e) => finish({ error: 'Не удалось открыть локальный порт: ' + e.message }));
        server.listen(0, '127.0.0.1', () => {
            port = server.address().port;
            const auth = new URL('https://accounts.google.com/o/oauth2/v2/auth');
            auth.search = new URLSearchParams({
                client_id: clientId, redirect_uri: `http://127.0.0.1:${port}`, response_type: 'code', scope: SCOPE,
                code_challenge: challenge, code_challenge_method: 'S256', access_type: 'offline', prompt: 'consent', state,
            }).toString();
            shell.openExternal(auth.toString());
        });
    });
}

let loginBusy = false;
ipcMain.handle('gauth:login', async (_e, clientId, clientSecret) => {
    if (!clientId) return { error: 'Не указан Client ID' };
    if (loginBusy) return { error: 'Окно входа уже открыто в браузере' };
    loginBusy = true;
    try {
        const verifier = b64url(crypto.randomBytes(48));
        const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
        const state = b64url(crypto.randomBytes(16));
        const r = await waitForCode(clientId, challenge, state);
        if (r.error) return { error: r.error };
        const params = { code: r.code, client_id: clientId, redirect_uri: r.redirect, grant_type: 'authorization_code', code_verifier: verifier };
        if (clientSecret) params.client_secret = clientSecret;
        const t = await tokenRequest(params);
        if (!t.refresh_token) return { error: 'Google не выдал ключ продления. Удалите доступ приложения в настройках аккаунта Google и войдите снова.' };
        saveAuth({ clientId, clientSecret: clientSecret || '', refreshToken: t.refresh_token });
        accessCache = { token: t.access_token, exp: Date.now() + (t.expires_in || 3600) * 1000 };
        BrowserWindow.getAllWindows().forEach(w => { if (w.isMinimized()) w.restore(); w.focus(); });
        return { access_token: t.access_token, expires_in: t.expires_in || 3600 };
    } catch (err) {
        return { error: String(err.message || err) };
    } finally {
        loginBusy = false;
    }
});

ipcMain.handle('gauth:token', async () => {
    if (accessCache.token && Date.now() < accessCache.exp - 60000) return { access_token: accessCache.token, expires_in: Math.round((accessCache.exp - Date.now()) / 1000) };
    const a = loadAuth();
    if (!a || !a.refreshToken) return { error: 'login' };
    try {
        const params = { client_id: a.clientId, refresh_token: a.refreshToken, grant_type: 'refresh_token' };
        if (a.clientSecret) params.client_secret = a.clientSecret;
        const t = await tokenRequest(params);
        accessCache = { token: t.access_token, exp: Date.now() + (t.expires_in || 3600) * 1000 };
        return { access_token: t.access_token, expires_in: t.expires_in || 3600 };
    } catch (err) {
        if (/invalid_grant/i.test(String(err.message))) { try { fs.unlinkSync(authFile()); } catch {} return { error: 'login' }; }
        return { error: String(err.message || err) };
    }
});

ipcMain.handle('gauth:status', () => { const a = loadAuth(); return { loggedIn: !!(a && a.refreshToken), clientId: a ? a.clientId : '' }; });

ipcMain.handle('gauth:logout', async () => {
    const a = loadAuth();
    if (a && a.refreshToken) await doRequest({ url: 'https://oauth2.googleapis.com/revoke?token=' + encodeURIComponent(a.refreshToken), method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
    try { fs.unlinkSync(authFile()); } catch {}
    accessCache = { token: null, exp: 0 };
    return { ok: true };
});

/* ===================== Голосовой ввод: Whisper офлайн =====================
   Распознавание идёт на этом компьютере (модель Whisper от OpenAI через transformers.js).
   Модель скачивается один раз (~250 МБ) в папку данных программы, дальше работает без интернета. */
const ASR_MODELS = {
    small: { id: 'onnx-community/whisper-small', dtype: { encoder_model: 'q8', decoder_model_merged: 'q8' } },
    base: { id: 'onnx-community/whisper-base', dtype: { encoder_model: 'fp32', decoder_model_merged: 'q8' } },
};
const MIRRORS = ['https://huggingface.co/', 'https://hf-mirror.com/'];
let asr = { pipe: null, key: null, loading: null };

async function loadASR(key, sender) {
    if (asr.pipe && asr.key === key) return asr.pipe;
    if (asr.loading && asr.key === key) return asr.loading;
    asr.key = key;
    asr.loading = (async () => {
        let T;
        try { T = await import('@huggingface/transformers'); }
        catch (e) { throw new Error('Модуль распознавания не установлен — запустите «Установить голосовой ввод.bat»'); }
        T.env.cacheDir = path.join(app.getPath('userData'), 'models');
        T.env.allowLocalModels = true;
        T.env.localModelPath = path.join(__dirname, 'models');   // можно положить модель рядом с программой
        T.env.fetch = (u, init) => net.fetch(String(u), init);   // те же настройки сети/прокси, что у Windows
        const m = ASR_MODELS[key] || ASR_MODELS.small;
        const files = {};
        const progress = (p) => {
            if (!sender || sender.isDestroyed()) return;
            if (p.status === 'progress' && p.file) { files[p.file] = { loaded: p.loaded || 0, total: p.total || 0 }; }
            const all = Object.values(files), total = all.reduce((a, f) => a + f.total, 0), loaded = all.reduce((a, f) => a + f.loaded, 0);
            sender.send('asr:progress', { status: p.status, percent: total ? Math.round(loaded / total * 100) : null, loadedMB: Math.round(loaded / 1048576) , totalMB: Math.round(total / 1048576) });
        };
        let lastErr;
        for (const host of MIRRORS) {
            T.env.remoteHost = host;
            try {
                const pipe = await T.pipeline('automatic-speech-recognition', m.id, { dtype: m.dtype, device: 'cpu', progress_callback: progress });
                asr.pipe = pipe; return pipe;
            } catch (e) { lastErr = e; if (!/fetch|network|ENOTFOUND|ECONN|ETIMEDOUT|40[34]|5\d\d/i.test(String(e && e.message))) break; }
        }
        throw new Error('Не удалось загрузить модель распознавания: ' + (lastErr && lastErr.message || lastErr));
    })();
    try { return await asr.loading; }
    catch (e) { asr.key = null; throw e; }
    finally { asr.loading = null; }
}

ipcMain.handle('asr:prepare', async (e, key) => {
    try { await loadASR(key || 'small', e.sender); return { ok: true }; }
    catch (err) { return { error: String(err.message || err) }; }
});
ipcMain.handle('asr:transcribe', async (e, audio, key) => {
    try {
        const pipe = await loadASR(key || 'small', e.sender);
        const data = audio instanceof Float32Array ? audio : new Float32Array(audio);
        if (!data.length) return { text: '' };
        const out = await pipe(data, { language: 'russian', task: 'transcribe', chunk_length_s: 30, stride_length_s: 5 });
        const text = (Array.isArray(out) ? out.map(o => o.text).join(' ') : out.text || '').replace(/\s+/g, ' ').trim();
        return { text };
    } catch (err) { return { error: String(err.message || err) }; }
});
ipcMain.handle('asr:status', () => {
    let installed = true;
    try { require.resolve('@huggingface/transformers'); } catch { installed = false; }
    const dir = path.join(app.getPath('userData'), 'models');
    const has = (id) => { try { return fs.readdirSync(path.join(dir, ...id.split('/'), 'onnx')).length > 0; } catch { return false; } };
    return { installed, models: { small: has(ASR_MODELS.small.id), base: has(ASR_MODELS.base.id) } };
});

// Диктовка Windows (Win+H) — запасной вариант
// Держим PowerShell запущенным: первый вызов ~1 с, дальше нажатие Win+H срабатывает мгновенно
let psProc = null;
function getPS() {
    if (psProc && !psProc.killed && psProc.exitCode === null) return psProc;
    const { spawn } = require('child_process');
    psProc = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-NoLogo', '-Command', '-'], { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] });
    psProc.stdin.write("Add-Type -Name K -Namespace W -MemberDefinition '[DllImport(\"user32.dll\")] public static extern void keybd_event(byte b, byte s, int f, int e);'\n");
    psProc.on('exit', () => { psProc = null; });
    return psProc;
}
ipcMain.handle('sys:dictation', () => {
    if (process.platform !== 'win32') return { error: 'Только для Windows' };
    try {
        getPS().stdin.write('[W.K]::keybd_event(0x5B,0,0,0); [W.K]::keybd_event(0x48,0,0,0); Start-Sleep -Milliseconds 30; [W.K]::keybd_event(0x48,0,2,0); [W.K]::keybd_event(0x5B,0,2,0)\n');
        return { ok: true };
    } catch (err) { psProc = null; return { error: String(err.message || err) }; }
});
if (process.platform === 'win32') app.whenReady().then(() => setTimeout(() => { try { getPS(); } catch {} }, 4000));
app.on('will-quit', () => { if (psProc) { try { psProc.stdin.end(); psProc.kill(); } catch {} } });

/* ===================== Окно ===================== */
function createWindow() {
    const win = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 400,
        minHeight: 300,
        backgroundColor: '#05353d',
        title: 'Задачи',
        icon: path.join(__dirname, 'icon.ico'),
        autoHideMenuBar: true,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js'),
            spellcheck: true,
        }
    });

    // Ссылки (Google Календарь и т.п.) открываются в обычном браузере, а не внутри программы
    win.webContents.setWindowOpenHandler(({ url }) => {
        if (/^https?:\/\//i.test(url)) shell.openExternal(url);
        return { action: 'deny' };
    });

    // Разрешаем микрофон (для диктовки) и буфер обмена
    win.webContents.session.setPermissionRequestHandler((_wc, permission, cb) => {
        cb(['media', 'clipboard-read', 'clipboard-sanitized-write'].includes(permission));
    });
    win.webContents.session.setSpellCheckerLanguages(['ru', 'en-US']);

    win.loadFile('index.html');
    Menu.setApplicationMenu(null);
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
