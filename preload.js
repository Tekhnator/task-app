// Мост между страницей и программой: сетевые запросы (погода, праздники, календари, Google)
// и вход в Google выполняются главным процессом — их не блокируют ограничения file://.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('nativeNet', {
    isApp: true,
    get: (url, timeoutMs, headers) => ipcRenderer.invoke('net:get', String(url), Number(timeoutMs) || 10000, headers || {}),
    request: (opts) => ipcRenderer.invoke('net:request', opts || {}),
});

contextBridge.exposeInMainWorld('nativeGoogle', {
    login: (clientId, clientSecret) => ipcRenderer.invoke('gauth:login', String(clientId || ''), String(clientSecret || '')),
    token: () => ipcRenderer.invoke('gauth:token'),
    status: () => ipcRenderer.invoke('gauth:status'),
    logout: () => ipcRenderer.invoke('gauth:logout'),
});

contextBridge.exposeInMainWorld('nativeVoice', {
    prepare: (model) => ipcRenderer.invoke('asr:prepare', model),
    transcribe: (audio, model) => ipcRenderer.invoke('asr:transcribe', audio, model),
    status: () => ipcRenderer.invoke('asr:status'),
    winDictation: () => ipcRenderer.invoke('sys:dictation'),
    onProgress: (cb) => { const h = (_e, p) => cb(p); ipcRenderer.on('asr:progress', h); return () => ipcRenderer.removeListener('asr:progress', h); },
});
