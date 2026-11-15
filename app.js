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
  save(c) {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(c)); } catch { /* приватный режим */ }
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

let reloginPromise = null;
function relogin() {
  if (!cfg.login || !cfg.password) return Promise.reject(new ApiError('Нужно войти заново.', 401));
  // Параллельные запросы ждут один и тот же вход, а не логинятся каждый сам
  reloginPromise ??= login(cfg.login, cfg.password)
    .then((token) => { cfg.token = token; store.save(cfg); })
    .finally(() => { reloginPromise = null; });
  return reloginPromise;
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

// ===== Helpers =====
const $ = (id) => document.getElementById(id);

function show(screen) {
  for (const s of ['loginScreen', 'mainScreen', 'settingsScreen']) $(s).hidden = s !== screen;
  window.scrollTo(0, 0);
}

function setMsg(el, text, kind) {
  el.textContent = text || '';
  el.classList.remove('error', 'ok');
  if (kind) el.classList.add(kind);
}

function parseNum(v) { return parseFloat(String(v).replace(',', '.')); }

function formatTemp(v) {
  const n = parseNum(v);
  return isFinite(n) ? n.toFixed(1) : String(v ?? '—');
}

function formatTime(unix) {
  if (!unix) return '';
  const d = new Date(unix * 1000);
  const hm = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === new Date().toDateString()) return hm;
  return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }) + ' ' + hm;
}

function pickParam(params, preferId, def, writableOnly) {
  const list = params.filter((p) => !writableOnly || p.is_writable !== false);
  return (
    list.find((p) => p.id == preferId) ||
    list.find((p) => p.code === def.code) ||
    list.find((p) => def.name.test(p.name || ''))
  );
}

// ===== Главный экран =====
let timer = null;
let busy = false;

async function refresh() {
  if (!cfg.tempId || !cfg.resetId) return;
  const hero = document.querySelector('.hero');
  hero.classList.add('loading');
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
    hero.classList.toggle('cold', t && parseNum(t.v) < 40);
    $('updated').textContent = t ? 'Обновлено ' + formatTime(t.d) : 'Нет данных от прибора';

    const rv = r ? String(r.v) : null;
    const badge = $('resetBadge');
    badge.textContent = rv === null ? '—' : 'Сейчас: ' + rv;
    badge.className = 'badge' + (rv === '1' ? ' on' : rv === '0' ? ' off' : '');
    $('reset1').classList.toggle('active', rv === '1');
    $('reset0').classList.toggle('active', rv === '0');
    lastReset = rv;

    const dev = Array.isArray(devices) ? devices.find((d) => d.id == cfg.deviceId) : null;
    const status = dev && dev.status;
    const dot = $('statusDot');
    dot.className = 'dot';
    let label = 'нет связи с прибором';
    if (status === 'online') { dot.classList.add('online'); label = 'онлайн'; }
    else if (status === 'alarm' || status === 'unreadalarm') { dot.classList.add('alarm'); label = 'авария'; }
    else if (status === 'offline') { label = 'офлайн'; }
    else if (t) { dot.classList.add('online'); label = 'данные получены'; }
    $('statusText').textContent = label;
  } catch (e) {
    $('statusDot').className = 'dot stale';
    $('statusText').textContent = e.message;
    if (e.status === 401 && !cfg.password) { show('loginScreen'); }
  } finally {
    hero.classList.remove('loading');
  }
}
let lastReset = null;

function startTimer() {
  stopTimer();
  timer = setInterval(() => { if (!document.hidden && !$('mainScreen').hidden) refresh(); }, REFRESH_MS);
}
function stopTimer() { if (timer) clearInterval(timer); timer = null; }

function openMain() {
  show('mainScreen');
  $('deviceName').textContent = cfg.deviceName || 'Котёл';
  refresh();
  startTimer();
}

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
    setMsg(msg, `Команда отправлена, жду прибор…`, 'ok');
    // Запись идёт через облако асинхронно: несколько раз перечитываем значение
    let applied = false;
    for (const delay of [2000, 4000, 6000, 10000]) {
      await new Promise((r) => setTimeout(r, delay));
      await refresh();
      if (lastReset === String(value)) { applied = true; break; }
    }
    setMsg(msg, applied ? `Reset = ${value} ✓` : 'Команда в очереди — прибор применит её при следующем опросе', 'ok');
  } catch (e) {
    setMsg(msg, 'Не записалось: ' + e.message, 'error');
  } finally {
    busy = false;
    $('reset1').disabled = $('reset0').disabled = false;
  }
}

function writeErrorText(res) {
  const sms = 'в OwenCloud включено SMS-подтверждение записи — отключите его';
  const map = {
    wrong_sms_tag: sms, expired_sms_code: sms, wrong_sms_code: sms,
    parameter_not_writable: 'параметр недоступен для записи',
    invalid_value: 'недопустимое значение',
    can_not_write: 'прибор не принял запись',
  };
  return map[res.code] || res.message || 'ошибка записи';
}

// ===== Вход =====
$('loginForm').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const email = $('login').value.trim();
  const password = $('password').value;
  const btn = $('loginBtn');
  btn.disabled = true;
  setMsg($('loginMsg'), 'Вхожу…');
  try {
    const token = await login(email, password);
    cfg = { ...cfg, login: email, password, token };
    store.save(cfg);
    setMsg($('loginMsg'), 'Ищу прибор и параметры…');
    if (await autoSetup()) {
      setMsg($('loginMsg'), '');
      openMain();
    } else {
      setMsg($('loginMsg'), '');
      await openSettings();
    }
  } catch (e) {
    setMsg($('loginMsg'), e.message, 'error');
  } finally {
    btn.disabled = false;
  }
});

// Пытаемся сами найти прибор с P512 и P527. true — если нашли однозначно.
async function autoSetup() {
  const devices = (await call('device/index', {})) || [];
  devicesCache = devices;
  const found = [];
  for (const d of devices) {
    const info = await call('device/' + d.id, []);
    const params = (info && info.parameters) || [];
    const t = params.find((p) => p.code === DEFAULT_TEMP.code);
    const r = params.find((p) => p.code === DEFAULT_RESET.code);
    if (t && r) found.push({ d, t, r });
  }
  if (found.length !== 1) return false;
  const { d, t, r } = found[0];
  cfg = { ...cfg, deviceId: d.id, deviceName: d.name, tempId: t.id, resetId: r.id };
  store.save(cfg);
  return true;
}

// ===== Настройки =====
let devicesCache = [];

async function openSettings() {
  stopTimer();
  show('settingsScreen');
  $('accountEmail').textContent = cfg.login || '';
  $('confirmWrite').checked = cfg.confirmWrite !== false;
  $('closeSettings').hidden = !(cfg.tempId && cfg.resetId);
  try {
    await loadDevices();
  } catch (e) {
    setMsg($('paramMsg'), e.message, 'error');
  }
}

async function loadDevices() {
  if (!devicesCache.length) devicesCache = (await call('device/index', {})) || [];
  const sel = $('deviceSelect');
  sel.innerHTML = '';
  if (!devicesCache.length) setMsg($('paramMsg'), 'В аккаунте нет приборов.', 'error');
  devicesCache.forEach((d) => sel.add(new Option(d.name || ('Прибор ' + d.id), d.id)));
  if (cfg.deviceId && devicesCache.some((d) => d.id == cfg.deviceId)) sel.value = cfg.deviceId;
  await loadParams(sel.value);
}

async function loadParams(deviceId) {
  if (!deviceId) return;
  setMsg($('paramMsg'), 'Загружаю параметры…');
  const dev = await call('device/' + deviceId, []);
  const params = (dev && dev.parameters) || [];
  const same = deviceId == cfg.deviceId;
  const fill = (sel, preferId, def, writableOnly) => {
    sel.innerHTML = '';
    params
      .filter((p) => !writableOnly || p.is_writable !== false)
      .forEach((p) => sel.add(new Option(`${p.name} (${p.code})`, p.id)));
    const pick = pickParam(params, preferId, def, writableOnly);
    if (pick) sel.value = pick.id;
  };
  fill($('tempSelect'), same && cfg.tempId, DEFAULT_TEMP, false);
  fill($('resetSelect'), same && cfg.resetId, DEFAULT_RESET, true);
  setMsg($('paramMsg'), params.length ? '' : 'У прибора нет параметров.', params.length ? '' : 'error');
}

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
  openMain();
});

$('logout').addEventListener('click', () => {
  if (!confirm('Выйти и стереть логин, пароль и настройки с этого телефона?')) return;
  store.clear();
  cfg = {};
  devicesCache = [];
  stopTimer();
  $('password').value = '';
  show('loginScreen');
});

$('openSettings').addEventListener('click', openSettings);
$('closeSettings').addEventListener('click', openMain);
$('refresh').addEventListener('click', refresh);
$('reset1').addEventListener('click', () => writeReset(1));
$('reset0').addEventListener('click', () => writeReset(0));

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !$('mainScreen').hidden) refresh();
});

// ===== Инструкция по виджету =====
const isIOS = !/Android/i.test(navigator.userAgent) &&
  (/iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1));
$(isIOS ? 'helpIos' : 'helpAndroid').hidden = false;

// Код скриптов загружаем заранее: копирование в буфер должно произойти прямо в момент нажатия
const scripts = {};
$('widgetHelp').addEventListener('toggle', () => {
  if (!$('widgetHelp').open) return;
  for (const f of ['android/http-shortcuts.js', 'scriptable/Kotel.js']) {
    if (!scripts[f]) fetch(f).then((r) => r.text()).then((t) => { scripts[f] = t; }).catch(() => {});
  }
});

function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(text);
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand('copy');
  ta.remove();
  return ok ? Promise.resolve() : Promise.reject(new Error('copy failed'));
}

document.querySelectorAll('.copy').forEach((btn) => {
  btn.addEventListener('click', () => {
    const file = btn.dataset.file || 'android/http-shortcuts.js';
    let text = scripts[file];
    if (!text) { setMsg($('copyMsg'), 'Код ещё загружается, нажмите ещё раз', 'error'); return; }
    if (btn.dataset.action) {
      text = text.replace(/const ACTION = '[a-z0-9]+';/, `const ACTION = '${btn.dataset.action}';`);
    }
    copyText(text)
      .then(() => setMsg($('copyMsg'), 'Скопировано — вставьте в приложение', 'ok'))
      .catch(() => setMsg($('copyMsg'), 'Не удалось скопировать. Откройте ' + file + ' на GitHub', 'error'));
  });
});

// ===== Старт =====
if (cfg.tempId && cfg.resetId && (cfg.token || cfg.password)) {
  openMain();
  // Ссылка вида ?action=reset1 / ?action=reset0 (ярлыки на иконке приложения)
  const action = new URLSearchParams(location.search).get('action');
  if (action === 'reset1' || action === 'reset0') {
    history.replaceState(null, '', location.pathname);
    setTimeout(() => writeReset(action === 'reset1' ? 1 : 0), 300);
  }
} else if (cfg.token || cfg.password) {
  openSettings();
} else {
  show('loginScreen');
}

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
