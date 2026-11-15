// Виджет «Котёл» для приложения Scriptable (iPhone / iPad).
// Показывает температуру и Reset; в среднем виджете — кнопки RESET 1 / RESET 0.
//
// Установка:
//  1. Поставьте Scriptable из App Store.
//  2. В Scriptable нажмите «+», вставьте этот файл, назовите скрипт «Kotel».
//  3. Запустите скрипт один раз внутри Scriptable — он спросит email и пароль OwenCloud
//     и сам найдёт параметры Temp_E (P512) и Reset_E (P527).
//  4. На рабочем столе: долгое нажатие → «+» → Scriptable → средний размер →
//     нажмите на виджет → Script: Kotel.
//
// Логин, пароль и токен хранятся в Keychain (связке ключей) iPhone, а не в коде.

const API = 'https://api.owencloud.ru/v1/';
const KEY = 'owencloud-kotel';
const TEMP_CODE = 'P512';
const RESET_CODE = 'P527';

// Ссылка на сайт (GitHub Pages). Если заполнить, тап по температуре откроет сайт.
const SITE_URL = '';

// ---------- хранение настроек ----------
function loadCfg() {
  if (!Keychain.contains(KEY)) return {};
  try { return JSON.parse(Keychain.get(KEY)); } catch (e) { return {}; }
}
function saveCfg(c) { Keychain.set(KEY, JSON.stringify(c)); }

let cfg = loadCfg();

// ---------- API ----------
async function rawCall(path, body, token) {
  const req = new Request(API + path);
  req.method = 'POST';
  req.headers = { 'Content-Type': 'application/json' };
  if (token) req.headers.Authorization = 'Bearer ' + token;
  req.body = JSON.stringify(body == null ? {} : body);
  req.timeoutInterval = 20;
  const data = await req.loadJSON().catch(() => null);
  const status = req.response ? req.response.statusCode : 0;
  if (status === 401) throw { status: 401, message: 'Нужна авторизация' };
  if (status >= 400 || status === 0) {
    throw { status, message: (data && data.message) || ('Ошибка ' + status) };
  }
  return data;
}

async function relogin() {
  if (!cfg.login || !cfg.password) throw { status: 401, message: 'Откройте скрипт в Scriptable и войдите' };
  const data = await rawCall('auth/open', { login: cfg.login, password: cfg.password });
  if (!data || !data.token) throw { status: 401, message: 'Неверный email или пароль' };
  cfg.token = data.token;
  saveCfg(cfg);
}

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

async function readValues() {
  const rows = await call('parameters/last-data', { ids: [cfg.tempId, cfg.resetId] });
  const by = {};
  (rows || []).forEach((r) => { by[r.id] = (r.values && r.values[0]) || null; });
  return { temp: by[cfg.tempId], reset: by[cfg.resetId] };
}

async function writeReset(value) {
  const res = await call('parameters/write-data', {
    sms_tag: '', sms_code: '', timeout: 60, sync: false,
    data: [{ id: cfg.resetId, value: String(value) }],
  });
  if (typeof res === 'string') throw { message: res };
  if (res && res.status === 'error') throw { message: res.message || res.code || 'Ошибка записи' };
  return res;
}

// ---------- первичная настройка ----------
async function setup() {
  const a = new Alert();
  a.title = 'Вход в OwenCloud';
  a.addTextField('Email', cfg.login || '');
  a.addSecureTextField('Пароль', '');
  a.addAction('Войти');
  a.addCancelAction('Отмена');
  if ((await a.presentAlert()) === -1) return false;
  cfg = { login: a.textFieldValue(0).trim(), password: a.textFieldValue(1) };
  await relogin();

  const devices = await call('device/index', {});
  if (!devices || !devices.length) throw { message: 'В аккаунте нет приборов' };
  let dev = devices[0];
  if (devices.length > 1) {
    const pick = new Alert();
    pick.title = 'Выберите прибор';
    devices.forEach((d) => pick.addAction(d.name || ('Прибор ' + d.id)));
    dev = devices[await pick.presentSheet()];
  }

  const info = await call('device/' + dev.id, []);
  const params = (info && info.parameters) || [];
  const temp = params.find((p) => p.code === TEMP_CODE) || params.find((p) => /temp/i.test(p.name || ''));
  const reset = params.find((p) => p.code === RESET_CODE) || params.find((p) => /reset/i.test(p.name || ''));
  if (!temp || !reset) throw { message: `Не нашёл ${TEMP_CODE} / ${RESET_CODE} у прибора «${dev.name}»` };

  cfg.deviceId = dev.id;
  cfg.deviceName = dev.name;
  cfg.tempId = temp.id;
  cfg.resetId = reset.id;
  saveCfg(cfg);

  const ok = new Alert();
  ok.title = 'Готово';
  ok.message = `Прибор: ${dev.name}\nТемпература: ${temp.name}\nReset: ${reset.name}\n\nТеперь добавьте виджет Scriptable на рабочий стол и выберите скрипт «${Script.name()}».`;
  ok.addAction('OK');
  await ok.present();
  return true;
}

// ---------- виджет ----------
const C = {
  bg: Color.dynamic(new Color('#ffffff'), new Color('#1b2433')),
  text: Color.dynamic(new Color('#15181e'), new Color('#f2f4f8')),
  muted: Color.dynamic(new Color('#667085'), new Color('#9aa6b8')),
  danger: new Color('#e24b4a'),
  neutral: Color.dynamic(new Color('#e6e9ef'), new Color('#2a3547')),
  accent: new Color('#2f6fdb'),
};

function fmtTemp(v) {
  const n = parseFloat(String(v).replace(',', '.'));
  return isFinite(n) ? n.toFixed(1) : String(v);
}
function fmtTime(unix) {
  if (!unix) return '';
  const df = new DateFormatter();
  df.dateFormat = 'HH:mm';
  return df.string(new Date(unix * 1000));
}
function runUrl(action) {
  return `scriptable:///run/${encodeURIComponent(Script.name())}?action=${action}`;
}

function button(stack, title, bg, fg, url) {
  const b = stack.addStack();
  b.size = new Size(104, 40);
  b.cornerRadius = 12;
  b.backgroundColor = bg;
  b.centerAlignContent();
  b.url = url;
  const t = b.addText(title);
  t.font = Font.semiboldSystemFont(14);
  t.textColor = fg;
}

async function buildWidget(family) {
  const w = new ListWidget();
  w.backgroundColor = C.bg;
  w.setPadding(14, 16, 14, 16);
  w.refreshAfterDate = new Date(Date.now() + 5 * 60 * 1000);

  if (!cfg.tempId) {
    const t = w.addText('Откройте скрипт в Scriptable и войдите в OwenCloud');
    t.font = Font.systemFont(13);
    t.textColor = C.muted;
    return w;
  }

  let temp = null, reset = null, err = null;
  try { ({ temp, reset } = await readValues()); } catch (e) { err = e.message || String(e); }

  const row = w.addStack();
  row.layoutHorizontally();
  row.centerAlignContent();

  const left = row.addStack();
  left.layoutVertically();
  if (SITE_URL) left.url = SITE_URL;

  const title = left.addText('🔥 ' + (cfg.deviceName || 'Котёл'));
  title.font = Font.mediumSystemFont(12);
  title.textColor = C.muted;
  title.lineLimit = 1;
  left.addSpacer(4);

  const tv = left.addText(temp ? fmtTemp(temp.v) + '°' : '—');
  tv.font = Font.semiboldRoundedSystemFont(family === 'small' ? 38 : 42);
  tv.textColor = C.text;
  tv.minimumScaleFactor = 0.6;
  left.addSpacer(4);

  const meta = left.addText(err ? err : `Reset: ${reset ? reset.v : '—'} · ${fmtTime(temp && temp.d)}`);
  meta.font = Font.systemFont(11);
  meta.textColor = err ? C.danger : C.muted;
  meta.lineLimit = 2;

  if (family !== 'small') {
    row.addSpacer();
    const right = row.addStack();
    right.layoutVertically();
    const isOne = reset && String(reset.v) === '1';
    button(right, 'RESET 1', C.danger, Color.white(), runUrl('reset1'));
    right.addSpacer(8);
    button(right, 'RESET 0', isOne ? C.neutral : C.accent, isOne ? C.text : Color.white(), runUrl('reset0'));
  } else {
    w.url = SITE_URL || runUrl('open');
  }
  return w;
}

// ---------- обработка нажатия RESET ----------
async function handleAction(action) {
  const value = action === 'reset1' ? 1 : 0;
  const a = new Alert();
  a.title = `Записать Reset = ${value}?`;
  a.message = cfg.deviceName || '';
  a.addAction('Записать');
  a.addCancelAction('Отмена');
  if ((await a.presentAlert()) === -1) return;
  const n = new Notification();
  try {
    await writeReset(value);
    n.title = `Reset = ${value} отправлен`;
    n.body = 'Прибор применит команду в течение минуты.';
  } catch (e) {
    n.title = 'Не записалось';
    n.body = e.message || String(e);
  }
  await n.schedule();
}

// ---------- запуск ----------
const action = args.queryParameters && args.queryParameters.action;

if (config.runsInWidget) {
  Script.setWidget(await buildWidget(config.widgetFamily));
} else if (action === 'reset1' || action === 'reset0') {
  await handleAction(action);
  App.close();
} else {
  try {
    if (!cfg.tempId) {
      await setup();
    } else {
      const a = new Alert();
      a.title = cfg.deviceName || 'Котёл';
      a.addAction('Показать виджет');
      a.addAction('Войти заново / сменить прибор');
      a.addCancelAction('Закрыть');
      const i = await a.presentAlert();
      if (i === 0) (await buildWidget('medium')).presentMedium();
      if (i === 1) await setup();
    }
  } catch (e) {
    const er = new Alert();
    er.title = 'Ошибка';
    er.message = e.message || String(e);
    er.addAction('OK');
    await er.present();
  }
}
Script.complete();
