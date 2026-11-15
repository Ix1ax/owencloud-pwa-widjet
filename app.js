'use strict';

// ===== OwenCloud API =====
// Документация: https://api.owencloud.ru/
// Сервер разрешает запросы с любого сайта (CORS: *), но из заголовков пропускает только
// Authorization. Поэтому тело отправляем без Content-Type (браузер поставит text/plain) —
// сервер всё равно разбирает его как JSON.
const API = 'https://api.owencloud.ru/v1/';
const STORE_KEY = 'owencloud-widget';
const REFRESH_MS = 15000;

// Коды параметров по умолчанию (из таблицы OwenCloud)
const DEFAULT_TEMP = { code: 'P512', name: /temp/i };
const DEFAULT_RESET = { code: 'P527', name: /reset/i };

class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

const store = {
  load() {
    try { return JSON.parse(localStorage.getItem(STORE_KEY)) || {}; } catch { return {}; }
  },
  save(cfg) {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(cfg)); } catch { /* приватный режим */ }
  },
  clear() {
    try { localStorage.removeItem(STORE_KEY); } catch { /* ignore */ }
  },
};

let cfg = store.load();

async function rawCall(path, body, token) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  let res;
  try {
    res = await fetch(API + path, { method: 'POST', headers, body: JSON.stringify(body ?? {}) });
  } catch {
    throw new ApiError('Нет связи с OwenCloud. Проверьте интернет.', 0);
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    const msg = (data && data.message) || (typeof data === 'string' && data) || ('Ошибка ' + res.status);
    throw new ApiError(msg, res.status);
  }
  return data;
}

async function login(email, password) {
  const data = await rawCall('auth/open', { login: email, password });
  if (!data || !data.token) {
    throw new ApiError((data && data.message) || 'Неверный email или пароль.', 401);
  }
  return data.token;
}

// Вызов с токеном; при 401 один раз перелогиниваемся сохранённым паролем
async function call(path, body) {
  if (!cfg.token) await relogin();
  try {
    return await rawCall(path, body, cfg.token);
  } catch (e) {
    if (e.status !== 401) throw e;
    await relogin();
    return rawCall(path, body, cfg.token);
  }
}

let reloginPromise = null;
function relogin() {
  if (!cfg.login || !cfg.password) return Promise.reject(new ApiError('Нужно войти заново.', 401));
  // Параллельные запросы ждут один и тот же вход, а не логинятся каждый сам
  reloginPromise ??= login(cfg.login, cfg.password)
    .then((token) => { cfg.token = token; store.save(cfg); })
    .finally(() => { reloginPromise = null; });
  return reloginPromise;
}

// ===== Helpers =====
const $ = (id) => document.getElementById(id);

function show(screen) {
  $('main').hidden = screen !== 'main';
  $('settings').hidden = screen !== 'settings';
}

function setMsg(el, text, kind) {
  el.textContent = text || '';
  el.className = el.className.replace(/\s?(error|ok)\b/g, '') + (kind ? ' ' + kind : '');
}

function formatTemp(v) {
  const n = parseFloat(String(v).replace(',', '.'));
  if (!isFinite(n)) return String(v ?? '—');
  return n.toFixed(1);
}

function formatTime(unix) {
  if (!unix) return '';
  const d = new Date(unix * 1000);
  const now = new Date();
  const hm = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return hm;
  return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }) + ' ' + hm;
}

// ===== Main screen =====
let timer = null;
let busy = false;

async function refresh() {
  if (!cfg.tempId || !cfg.resetId) return;
  try {
    const [rows, devices] = await Promise.all([
      call('parameters/last-data', { ids: [cfg.tempId, cfg.resetId] }),
      call('device/index', { device_ids: [cfg.deviceId] }).catch(() => null),
    ]);
    const byId = {};
    (rows || []).forEach((r) => { byId[r.id] = (r.values && r.values[0]) || null; });

    const t = byId[cfg.tempId];
    const r = byId[cfg.resetId];
    $('tempValue').textContent = t ? formatTemp(t.v) : '—';
    $('resetValue').textContent = r ? String(r.v) : '—';
    $('reset1').classList.toggle('active', r && String(r.v) === '1');
    $('reset0').classList.toggle('active', r && String(r.v) === '0');

    const dev = Array.isArray(devices) ? devices.find((d) => d.id == cfg.deviceId) : null;
    const status = dev && dev.status;
    const dot = $('statusDot');
    dot.className = 'dot';
    let label = 'нет данных';
    if (status === 'online') { dot.classList.add('online'); label = 'онлайн'; }
    else if (status === 'alarm' || status === 'unreadalarm') { dot.classList.add('alarm'); label = 'авария'; }
    else if (status === 'offline') { label = 'офлайн'; }
    else if (t) { dot.classList.add('online'); label = 'данные получены'; }
    const when = t ? formatTime(t.d) : '';
    $('statusText').textContent = when ? `${label} · ${when}` : label;
  } catch (e) {
    $('statusDot').className = 'dot stale';
    $('statusText').textContent = e.message;
    if (e.status === 401 && !cfg.password) openSettings();
  }
}

function startTimer() {
  stopTimer();
  timer = setInterval(() => { if (!document.hidden) refresh(); }, REFRESH_MS);
}
function stopTimer() { if (timer) clearInterval(timer); timer = null; }

async function writeReset(value) {
  if (busy) return;
  if (cfg.confirmWrite !== false && !confirm(`Записать Reset = ${value}?`)) return;
  busy = true;
  const msg = $('writeMsg');
  $('reset1').disabled = $('reset0').disabled = true;
  setMsg(msg, 'Отправляю…');
  try {
    const res = await call('parameters/write-data', {
      sms_tag: '', sms_code: '', timeout: 60, sync: false,
      data: [{ id: cfg.resetId, value: String(value) }],
    });
    if (typeof res === 'string') throw new ApiError(res);
    if (res && res.status === 'error') throw new ApiError(writeErrorText(res));
    setMsg(msg, `Команда Reset = ${value} отправлена. Жду ответ прибора…`, 'ok');
    // Запись идёт через облако асинхронно: несколько раз перечитываем значение
    for (const delay of [2000, 4000, 6000, 10000]) {
      await new Promise((r) => setTimeout(r, delay));
      await refresh();
      if ($('resetValue').textContent === String(value)) {
        setMsg(msg, `Reset = ${value} ✓`, 'ok');
        break;
      }
    }
  } catch (e) {
    setMsg(msg, 'Не записалось: ' + e.message, 'error');
  } finally {
    busy = false;
    $('reset1').disabled = $('reset0').disabled = false;
  }
}

function writeErrorText(res) {
  const map = {
    wrong_sms_tag: 'в аккаунте включено SMS-подтверждение записи — отключите его в OwenCloud',
    expired_sms_code: 'в аккаунте включено SMS-подтверждение записи — отключите его в OwenCloud',
    wrong_sms_code: 'в аккаунте включено SMS-подтверждение записи — отключите его в OwenCloud',
    parameter_not_writable: 'параметр недоступен для записи',
    invalid_value: 'недопустимое значение',
    can_not_write: 'прибор не принял запись',
  };
  return map[res.code] || res.message || 'ошибка записи';
}

// ===== Settings =====
let devicesCache = [];

function openSettings() {
  stopTimer();
  show('settings');
  $('login').value = cfg.login || '';
  $('password').value = cfg.password || '';
  $('closeSettings').hidden = !(cfg.tempId && cfg.resetId);
  $('confirmWrite').checked = cfg.confirmWrite !== false;
  if (cfg.token) loadDevices().catch(() => {});
}

function closeSettings() {
  show('main');
  $('deviceName').textContent = cfg.deviceName || 'Котёл';
  refresh();
  startTimer();
}

async function loadDevices() {
  const list = await call('device/index', {});
  devicesCache = Array.isArray(list) ? list : [];
  const sel = $('deviceSelect');
  sel.innerHTML = '';
  if (!devicesCache.length) {
    setMsg($('paramMsg'), 'В аккаунте нет приборов.', 'error');
  }
  devicesCache.forEach((d) => sel.add(new Option(d.name || ('Прибор ' + d.id), d.id)));
  if (cfg.deviceId && devicesCache.some((d) => d.id == cfg.deviceId)) sel.value = cfg.deviceId;
  $('paramForm').hidden = false;
  await loadParams(sel.value);
}

async function loadParams(deviceId) {
  if (!deviceId) return;
  setMsg($('paramMsg'), 'Загружаю параметры…');
  const dev = await call('device/' + deviceId, []);
  const params = (dev && dev.parameters) || [];
  const fill = (sel, preferId, def, writableOnly) => {
    sel.innerHTML = '';
    params
      .filter((p) => !writableOnly || p.is_writable !== false)
      .forEach((p) => sel.add(new Option(`${p.name} (${p.code})`, p.id)));
    const pick =
      params.find((p) => p.id == preferId) ||
      params.find((p) => p.code === def.code) ||
      params.find((p) => def.name.test(p.name || ''));
    if (pick) sel.value = pick.id;
  };
  const same = deviceId == cfg.deviceId;
  fill($('tempSelect'), same && cfg.tempId, DEFAULT_TEMP, false);
  fill($('resetSelect'), same && cfg.resetId, DEFAULT_RESET, true);
  setMsg($('paramMsg'), params.length ? '' : 'У прибора нет параметров.', params.length ? '' : 'error');
}

$('loginForm').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const email = $('login').value.trim();
  const password = $('password').value;
  setMsg($('loginMsg'), 'Вхожу…');
  try {
    const token = await login(email, password);
    cfg = { ...cfg, login: email, password, token };
    store.save(cfg);
    setMsg($('loginMsg'), 'Вход выполнен', 'ok');
    await loadDevices();
  } catch (e) {
    setMsg($('loginMsg'), e.message, 'error');
  }
});

$('deviceSelect').addEventListener('change', (ev) => {
  loadParams(ev.target.value).catch((e) => setMsg($('paramMsg'), e.message, 'error'));
});

$('paramForm').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const tempId = Number($('tempSelect').value);
  const resetId = Number($('resetSelect').value);
  if (!tempId || !resetId) {
    setMsg($('paramMsg'), 'Выберите оба параметра.', 'error');
    return;
  }
  const deviceId = Number($('deviceSelect').value);
  const dev = devicesCache.find((d) => d.id == deviceId);
  cfg = {
    ...cfg,
    deviceId,
    deviceName: dev ? dev.name : 'Котёл',
    tempId,
    resetId,
    confirmWrite: $('confirmWrite').checked,
  };
  store.save(cfg);
  closeSettings();
});

$('logout').addEventListener('click', () => {
  if (!confirm('Стереть логин, пароль и настройки с этого телефона?')) return;
  store.clear();
  cfg = {};
  $('paramForm').hidden = true;
  setMsg($('loginMsg'), '');
  openSettings();
});

$('openSettings').addEventListener('click', openSettings);
$('closeSettings').addEventListener('click', closeSettings);
$('refresh').addEventListener('click', refresh);
$('reset1').addEventListener('click', () => writeReset(1));
$('reset0').addEventListener('click', () => writeReset(0));

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !$('main').hidden) refresh();
});

// ===== Start =====
if (cfg.tempId && cfg.resetId && (cfg.token || cfg.password)) {
  closeSettings();
  // Ссылка вида ?action=reset1 / ?action=reset0 (для ярлыков и виджетов)
  const action = new URLSearchParams(location.search).get('action');
  if (action === 'reset1' || action === 'reset0') {
    history.replaceState(null, '', location.pathname);
    setTimeout(() => writeReset(action === 'reset1' ? 1 : 0), 300);
  }
} else {
  openSettings();
}

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
