// Виджет «Котёл» для Android через бесплатное приложение HTTP Shortcuts
// (Google Play / F-Droid: «HTTP Shortcuts», автор Roland Meyer).
//
// Один и тот же код вставляется в три ярлыка типа «Scripting».
// Отличается только первая строка ACTION:
//   «Котёл обновить» → ACTION = 'read'
//   «RESET 1»        → ACTION = 'reset1'
//   «RESET 0»        → ACTION = 'reset0'
//
// Глобальные переменные (меню ⋮ → Variables → +, тип «Static / Constant»):
//   oc_login     — email OwenCloud
//   oc_password  — пароль OwenCloud
//   kotel        — пусто (сюда пишется текст для виджета)
//   kotel_cache  — пусто (служебная: токен и ID параметров)
//
// Всё хранится только в приложении на телефоне. Запросы идут напрямую на api.owencloud.ru.

const ACTION = 'read';

const TEMP_CODE = 'P512';  // Temp_E
const RESET_CODE = 'P527'; // Reset_E
const API = 'https://api.owencloud.ru/v1/';

function v(name) {
  try { return getVariable(name); } catch (e) { return ''; }
}

let cache = {};
try { cache = JSON.parse(v('kotel_cache') || '{}'); } catch (e) { cache = {}; }
function save() { setVariable('kotel_cache', JSON.stringify(cache)); }

function post(path, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  const r = sendHttpRequest(API + path, { method: 'POST', headers, body: JSON.stringify(body) });
  if (r.status === 'networkError' || !r.response) throw new Error('Нет связи с OwenCloud');
  let data;
  try { data = JSON.parse(r.response.body); } catch (e) { data = r.response.body; }
  return { code: r.response.statusCode, data };
}

function login() {
  if (!v('oc_login') || !v('oc_password')) throw new Error('Заполните переменные oc_login и oc_password');
  const r = post('auth/open', { login: v('oc_login'), password: v('oc_password') });
  if (!r.data || !r.data.token) throw new Error('Неверный email или пароль');
  cache.token = r.data.token;
  save();
}

// Запрос с токеном; при 401 один раз входим заново
function call(path, body) {
  if (!cache.token) login();
  let r = post(path, body, cache.token);
  if (r.code === 401) { login(); r = post(path, body, cache.token); }
  if (r.code >= 400) throw new Error((r.data && r.data.message) || ('Ошибка ' + r.code));
  return r.data;
}

// Находим параметры P512 / P527 среди приборов аккаунта (один раз, потом из кэша)
function ensureIds() {
  if (cache.tempId && cache.resetId) return;
  const devices = call('device/index', {}) || [];
  for (const d of devices) {
    const info = call('device/' + d.id, []);
    const params = (info && info.parameters) || [];
    const t = params.find((p) => p.code === TEMP_CODE);
    const rs = params.find((p) => p.code === RESET_CODE);
    if (t && rs) {
      cache.tempId = t.id;
      cache.resetId = rs.id;
      cache.device = d.name;
      save();
      return;
    }
  }
  throw new Error('Не нашёл параметры ' + TEMP_CODE + ' / ' + RESET_CODE);
}

function two(n) { return (n < 10 ? '0' : '') + n; }

function readValues() {
  const rows = call('parameters/last-data', { ids: [cache.tempId, cache.resetId] }) || [];
  const by = {};
  rows.forEach((r) => { by[r.id] = (r.values && r.values[0]) || null; });
  const t = by[cache.tempId];
  const rs = by[cache.resetId];
  const n = t ? parseFloat(String(t.v).replace(',', '.')) : NaN;
  const temp = isFinite(n) ? n.toFixed(1) + '°' : '—';
  const d = t && t.d ? new Date(t.d * 1000) : new Date();
  const time = two(d.getHours()) + ':' + two(d.getMinutes());
  setVariable('kotel', temp + '\nReset: ' + (rs ? rs.v : '—') + ' · ' + time);
}

function writeReset(value) {
  if (!confirm('Записать Reset = ' + value + '?')) return;
  const res = call('parameters/write-data', {
    sms_tag: '', sms_code: '', timeout: 60, sync: false,
    data: [{ id: cache.resetId, value: String(value) }],
  });
  if (typeof res === 'string') throw new Error(res);
  if (res && res.status === 'error') throw new Error(res.message || res.code || 'Ошибка записи');
  showToast('Reset = ' + value + ' отправлен');
}

try {
  ensureIds();
  if (ACTION === 'reset1') writeReset(1);
  else if (ACTION === 'reset0') writeReset(0);
  else readValues();
} catch (e) {
  // Если сменились приборы/параметры — в следующий раз ищем заново
  if (/Не нашёл/.test(e.message)) { delete cache.tempId; delete cache.resetId; save(); }
  showToast('Котёл: ' + e.message);
}
