import { CMD, STATE, STATE_NAME, ERRORS, cmd } from './protocol.js';
import { Treadmill } from './treadmill.js';
import { Simulator } from './simulator.js';
import { HeartRateMonitor } from './heartrate.js';

const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* storage unavailable */ } },
};

const settings = Object.assign({ units: 'km', weight: 65, step: 5, awake: true, theme: '', service: '' }, store.get('tm.settings', {}));
const saveSettings = () => store.set('tm.settings', settings);

const tm = new Treadmill();
const hrm = new HeartRateMonitor();
let info = {};
let state = null;
let live = null;
let session = null;
let lastCmd = { speed: 0, incline: 0, at: 0 };
let watchHr = { bpm: 0, at: 0 };
let goalStopSent = false;
let wakeLock = null;

// ---------- formatting ----------
const MI = 1609.344;
const pad = (n) => String(n).padStart(2, '0');
const hms = (s) => `${Math.floor(s / 3600)}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}`;
const ms = (s) => (isFinite(s) && s > 0 && s < 360000 ? `${Math.floor(s / 60)}:${pad(Math.round(s % 60))}` : '--:--');
const distUnit = () => (settings.units === 'mi' ? 'mi' : 'km');
const toDist = (m) => (settings.units === 'mi' ? m / MI : m / 1000);
const speedUnit = () => (info.unit === 1 ? 'mph' : 'km/h');
const minSpeed = () => info.minSpeed || 10;
const maxSpeed = () => info.maxSpeed || 120;

// ---------- connection ----------
$('connectBtn').onclick = async () => {
  if (tm.connected) { tm.disconnect(); return; }
  try { await tm.connectBluetooth(settings.service.trim()); }
  catch (e) { if (e.name !== 'NotFoundError') showAlert('Could not connect', e.message); setConn('Not connected'); }
};
$('demoBtn').onclick = () => {
  if (tm.connected) tm.disconnect();
  tm.connectSimulator(new Simulator());
  showTab('run');
};

tm.addEventListener('connecting', (e) => setConn(`Connecting to ${e.detail.name}…`));
tm.addEventListener('connected', (e) => {
  setConn(e.detail.name, true);
  $('connectBtn').textContent = 'Disconnect';
  $('connectBtn').classList.remove('primary');
  info = {};
  state = null;
  hideAlert();
  if (settings.weight > 0) tm.send(cmd.user(0, Math.min(255, Math.round(settings.weight))));
  requestInfo();
  render();
});
tm.addEventListener('disconnected', () => {
  if (session) finishSession();
  setConn('Not connected');
  $('connectBtn').textContent = 'Connect';
  $('connectBtn').classList.add('primary');
  state = null;
  render();
});
tm.addEventListener('log', (e) => addLog(e.detail));
tm.addEventListener('message', (e) => onMessage(e.detail));

function requestInfo() {
  tm.send(cmd.infoModel());
  tm.send(cmd.infoSpeed());
  tm.send(cmd.infoIncline());
  tm.send(cmd.infoTotal());
}
$('refreshInfo').onclick = requestInfo;

function setConn(text, on = false) {
  $('conn').textContent = text;
  $('conn').classList.toggle('on', on);
}

// ---------- incoming data ----------
function onMessage(m) {
  if (m.type === 'info') {
    if (m.sub === 'model') Object.assign(info, { model: m.model, modelName: m.modelName, serial: m.serial });
    if (m.sub === 'speed') Object.assign(info, { maxSpeed: m.maxSpeed, minSpeed: m.minSpeed, unit: m.unit });
    if (m.sub === 'incline') Object.assign(info, { maxIncline: m.maxIncline, minIncline: m.minIncline });
    if (m.sub === 'total') info.total = m.total;
    if (m.sub === 'date') info.date = m.date;
    buildPresets();
    render();
    return;
  }
  if (m.type === 'summary') { if (session) session.summary = m; return; }
  if (m.type !== 'status') return;

  state = m.state;

  if (m.live) live = m.live;

  if (state === STATE.START) {
    $('countdown').classList.remove('hidden');
    $('countdownN').textContent = m.countdown || '';
    hideAlert();
  } else {
    $('countdown').classList.add('hidden');
  }

  if ((state === STATE.START || state === STATE.RUNNING) && !session) {
    session = { start: Date.now(), hrSum: 0, hrN: 0, peakSpeed: 0, summaryAsked: false };
    goalStopSent = false;
    live = null;
    acquireWake();
  }

  if (session && m.live) {
    session.last = m.live;
    session.peakSpeed = Math.max(session.peakSpeed, m.live.speed);
    const bpm = currentHr();
    if (bpm && state === STATE.RUNNING) { session.hrSum += bpm; session.hrN++; }
    checkGoal();
  }

  if (session && (state === STATE.END || state === STATE.STOPPING) && !session.summaryAsked) {
    session.summaryAsked = true;
    tm.send(cmd.sportSummary());
  }

  if (state === STATE.ERROR) showAlert(`Error E${pad(m.error ?? 0)}`, ERRORS[m.error] || 'Treadmill fault');
  if (state === STATE.SAFETY) showAlert('E00', ERRORS[0] + '. Put the safety key back on the treadmill.');

  if (session && (state === STATE.NORMAL || state === STATE.ERROR || state === STATE.SAFETY)) finishSession();

  render();
}

function currentHr() {
  if (watchHr.bpm && Date.now() - watchHr.at < 5000) return watchHr.bpm;
  return live && live.heartRate ? live.heartRate : 0;
}

// ---------- controls ----------
$('startBtn').onclick = () => { hideAlert(); tm.send(cmd.start()); };
$('stopBtn').onclick = () => tm.send(cmd.stop());

function replaceQueued(payload) {
  tm.queue = tm.queue.filter((q) => !(q.data[0] === CMD.CONTROL && q.data[1] === 0x02));
  tm.send(payload);
}

function baseSpeed() { return Date.now() - lastCmd.at < 2500 ? lastCmd.speed : (live ? live.speed : minSpeed()); }
function baseIncline() { return Date.now() - lastCmd.at < 2500 ? lastCmd.incline : (live ? live.incline : 0); }

function setSpeed(raw) {
  if (state !== STATE.RUNNING) return;
  raw = Math.max(minSpeed(), Math.min(maxSpeed(), Math.round(raw)));
  lastCmd = { speed: raw, incline: baseIncline(), at: Date.now() };
  replaceQueued(cmd.speed(raw));
  render();
}
function setIncline(v) {
  if (state !== STATE.RUNNING) return;
  v = Math.max(info.minIncline || 0, Math.min(info.maxIncline || 0, Math.round(v)));
  lastCmd = { speed: baseSpeed(), incline: v, at: Date.now() };
  replaceQueued(cmd.speedIncline(lastCmd.speed, v));
  render();
}
$('speedUp').onclick = () => setSpeed(baseSpeed() + Number(settings.step));
$('speedDown').onclick = () => setSpeed(baseSpeed() - Number(settings.step));
$('inclineUp').onclick = () => setIncline(baseIncline() + 1);
$('inclineDown').onclick = () => setIncline(baseIncline() - 1);

function buildPresets() {
  const sp = [2, 4, 6, 8, 10, 12, 14, 16].map((k) => k * 10).filter((r) => r >= minSpeed() && r <= maxSpeed()).slice(0, 6);
  $('speedPresets').innerHTML = sp.map((r) => `<button data-s="${r}">${r / 10}</button>`).join('');
  $('speedPresets').querySelectorAll('button').forEach((b) => { b.onclick = () => setSpeed(+b.dataset.s); });
  const max = info.maxIncline || 0;
  $('inclineCard').classList.toggle('hidden', !max);
  const step = max > 8 ? 2 : 1;
  const ip = [];
  for (let i = info.minIncline || 0; i <= max && ip.length < 7; i += step) ip.push(i);
  $('inclinePresets').innerHTML = ip.map((v) => `<button data-i="${v}">${v}</button>`).join('');
  $('inclinePresets').querySelectorAll('button').forEach((b) => { b.onclick = () => setIncline(+b.dataset.i); });
}

// ---------- goal ----------
$('goalType').onchange = () => {
  const t = $('goalType').value;
  $('goalValue').disabled = t === 'none';
  $('goalValue').placeholder = { none: 'Value', time: 'Minutes', distance: distUnit(), calories: 'kcal' }[t];
  goalStopSent = false;
  render();
};
$('goalValue').oninput = () => { goalStopSent = false; render(); };

function goalProgress() {
  const t = $('goalType').value;
  const v = parseFloat($('goalValue').value);
  if (t === 'none' || !(v > 0) || !live) return 0;
  if (t === 'time') return live.time / (v * 60);
  if (t === 'distance') return toDist(live.distance) / v;
  return live.calories / 10 / v;
}
function checkGoal() {
  if (state === STATE.RUNNING && !goalStopSent && goalProgress() >= 1) {
    goalStopSent = true;
    tm.send(cmd.stop());
  }
}

// ---------- sessions / history ----------
function finishSession() {
  const s = session;
  session = null;
  releaseWake();
  if (!s || !s.last) return;
  const sum = s.summary && s.summary.distance >= s.last.distance ? s.summary : s.last;
  const rec = {
    date: s.start,
    time: sum.time,
    distance: sum.distance,
    calories: sum.calories / 10,
    steps: sum.steps,
    peakSpeed: s.peakSpeed / 10,
    avgHr: s.hrN ? Math.round(s.hrSum / s.hrN) : 0,
    speedUnit: speedUnit(),
  };
  if (rec.time < 30 && rec.distance < 10) return;
  const h = store.get('tm.history', []);
  h.push(rec);
  store.set('tm.history', h);
  renderHistory();
}

function renderHistory() {
  const h = store.get('tm.history', []);
  const totDist = h.reduce((a, r) => a + r.distance, 0);
  const totTime = h.reduce((a, r) => a + r.time, 0);
  const totCal = h.reduce((a, r) => a + r.calories, 0);
  $('totals').innerHTML = `
    <tr><td>Workouts</td><td>${h.length}</td></tr>
    <tr><td>Distance</td><td>${toDist(totDist).toFixed(2)} ${distUnit()}</td></tr>
    <tr><td>Time</td><td>${hms(totTime)}</td></tr>
    <tr><td>Calories</td><td>${Math.round(totCal)} kcal</td></tr>`;
  $('historyList').innerHTML = h.length ? h.slice().reverse().map((r) => {
    const d = new Date(r.date);
    const pace = r.distance ? r.time / toDist(r.distance) : 0;
    return `<div class="history-item">
      <div>${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
      <div class="n">${toDist(r.distance).toFixed(2)} ${distUnit()}</div>
      <div class="d">${hms(r.time)} · ${r.calories.toFixed(1)} kcal · ${r.steps} steps${r.avgHr ? ` · ${r.avgHr} bpm` : ''}</div>
      <div class="d">${ms(pace)} /${distUnit()}</div></div>`;
  }).join('') : '<p class="hint">No workouts yet. Runs are saved here automatically when the treadmill stops.</p>';
}

$('exportBtn').onclick = () => {
  const h = store.get('tm.history', []);
  const rows = [['date', 'duration_s', 'distance_m', 'calories_kcal', 'steps', 'peak_speed', 'speed_unit', 'avg_hr']]
    .concat(h.map((r) => [new Date(r.date).toISOString(), r.time, r.distance, r.calories, r.steps, r.peakSpeed, r.speedUnit, r.avgHr]));
  const blob = new Blob([rows.map((r) => r.join(',')).join('\n')], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `treadmill-history-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
};
$('clearHistory').onclick = () => {
  if (confirm('Delete all saved workouts? This cannot be undone.')) { store.set('tm.history', []); renderHistory(); }
};

// ---------- heart-rate monitor ----------
$('hrBtn').onclick = async () => {
  if (hrm.connected) { hrm.disconnect(); return; }
  try {
    const name = await hrm.connect();
    $('hrStatus').textContent = `Connected to ${name}. Waiting for heart rate…`;
    $('hrBtn').textContent = 'Disconnect';
  } catch (e) {
    if (e.name !== 'NotFoundError') $('hrStatus').textContent = e.message;
  }
};
hrm.addEventListener('bpm', (e) => {
  watchHr = { bpm: e.detail, at: Date.now() };
  $('hrStatus').textContent = `Connected · ${e.detail} bpm`;
  render();
});
hrm.addEventListener('disconnected', () => {
  watchHr = { bpm: 0, at: 0 };
  $('hrBtn').textContent = 'Connect';
  $('hrStatus').textContent = 'Heart-rate monitor disconnected.';
  render();
});

// ---------- wake lock ----------
async function acquireWake() {
  if (!settings.awake || !('wakeLock' in navigator)) return;
  try { wakeLock = await navigator.wakeLock.request('screen'); } catch { /* not allowed now */ }
}
function releaseWake() { if (wakeLock) { wakeLock.release().catch(() => {}); wakeLock = null; } }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && session) acquireWake(); });

// ---------- UI ----------
function showAlert(title, text) {
  $('alertTitle').textContent = title;
  $('alertText').textContent = text;
  $('alertBox').classList.remove('hidden');
}
function hideAlert() { $('alertBox').classList.add('hidden'); }

function addLog({ dir, text }) {
  const el = $('log');
  const t = new Date().toLocaleTimeString();
  el.textContent = `${t} ${dir} ${text}\n` + el.textContent.split('\n').slice(0, 200).join('\n');
}
$('clearLog').onclick = () => { $('log').textContent = ''; };

function render() {
  const conn = tm.connected;
  const running = state === STATE.RUNNING;
  const st = $('stateText');
  st.textContent = !conn ? 'Connect your treadmill to begin' : state == null ? 'Waiting for treadmill…' : STATE_NAME[state] || `State ${state}`;
  st.className = 'state' + (running ? ' running' : state === STATE.ERROR || state === STATE.SAFETY ? ' alert' : '');

  $('startBtn').disabled = !conn || !(state === STATE.NORMAL || state === STATE.END);
  $('stopBtn').disabled = !conn || !(state === STATE.RUNNING || state === STATE.START);
  ['speedUp', 'speedDown', 'inclineUp', 'inclineDown'].forEach((id) => { $(id).disabled = !running; });
  document.querySelectorAll('.presets button').forEach((b) => { b.disabled = !running; });
  $('refreshInfo').disabled = !conn;

  const shownSpeed = running && Date.now() - lastCmd.at < 2500 ? lastCmd.speed : live ? live.speed : 0;
  $('speedVal').textContent = (shownSpeed / 10).toFixed(1);
  $('speedUnit').textContent = speedUnit();
  $('inclineVal').textContent = running && Date.now() - lastCmd.at < 2500 ? lastCmd.incline : live ? live.incline : 0;

  const L = live || { time: 0, distance: 0, calories: 0, steps: 0 };
  const d = toDist(L.distance);
  $('sTime').textContent = hms(L.time);
  $('sDist').textContent = d.toFixed(2);
  $('sCal').textContent = (L.calories / 10).toFixed(1);
  $('sPace').textContent = d > 0.01 ? ms(L.time / d) : '--:--';
  $('sSteps').textContent = L.steps;
  const bpm = currentHr();
  $('sHr').textContent = bpm || '--';
  $('sAvg').textContent = L.time ? ((L.distance / 1000) / (L.time / 3600) / (info.unit === 1 ? 1.609344 : 1)).toFixed(1) : '0.0';
  $('sCad').textContent = L.time > 10 ? Math.round(L.steps / (L.time / 60)) : 0;
  document.querySelectorAll('.du').forEach((e) => { e.textContent = distUnit(); });
  document.querySelectorAll('.pu').forEach((e) => { e.textContent = '/' + distUnit(); });
  document.querySelectorAll('.su').forEach((e) => { e.textContent = speedUnit(); });

  $('goalBar').style.width = Math.min(100, goalProgress() * 100) + '%';

  const rows = [
    ['Model', info.modelName ?? '—'],
    ['Model number', info.model ?? '—'],
    ['Serial', info.serial ?? '—'],
    ['Speed range', info.maxSpeed ? `${info.minSpeed / 10} – ${info.maxSpeed / 10} ${speedUnit()}` : '—'],
    ['Incline range', info.maxIncline ? `${info.minIncline} – ${info.maxIncline}` : info.maxSpeed ? 'No incline' : '—'],
    ['Total distance', info.total ?? '—'],
  ];
  if (info.date) rows.push(['Factory date', info.date]);
  $('infoTable').innerHTML = rows.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('');
}

// ---------- tabs & settings ----------
function showTab(name) {
  ['run', 'history', 'device', 'settings'].forEach((t) => $('tab-' + t).classList.toggle('hidden', t !== name));
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  try { sessionStorage.setItem('tm.tab', name); } catch { /* ignore */ }
}
document.querySelectorAll('nav button').forEach((b) => { b.onclick = () => showTab(b.dataset.tab); });

function applyTheme() {
  if (settings.theme) document.documentElement.dataset.theme = settings.theme;
  else delete document.documentElement.dataset.theme;
}
$('setUnits').value = settings.units;
$('setWeight').value = settings.weight;
$('setStep').value = String(settings.step);
$('setAwake').checked = settings.awake;
$('setTheme').value = settings.theme;
$('setService').value = settings.service;
$('setUnits').onchange = (e) => { settings.units = e.target.value; saveSettings(); render(); renderHistory(); };
$('setWeight').onchange = (e) => { settings.weight = Number(e.target.value) || 0; saveSettings(); };
$('setStep').onchange = (e) => { settings.step = Number(e.target.value); saveSettings(); };
$('setAwake').onchange = (e) => { settings.awake = e.target.checked; saveSettings(); };
$('setTheme').onchange = (e) => { settings.theme = e.target.value; saveSettings(); applyTheme(); };
$('setService').onchange = (e) => { settings.service = e.target.value; saveSettings(); };

applyTheme();
buildPresets();
renderHistory();
render();
try { const t = sessionStorage.getItem('tm.tab'); if (t) showTab(t); } catch { /* ignore */ }
if (!navigator.bluetooth) {
  showAlert('Bluetooth not available in this browser', 'Open this page in Chrome on Android (or Chrome/Edge on a computer). You can still try the demo treadmill in Settings.');
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
