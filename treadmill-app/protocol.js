// E-RUNNING treadmill BLE protocol, reverse engineered from E-RUNNING 2.0.8 (com.zhongyang.ttpaobu).
// See PROTOCOL.md for the full description.
//
// Frame:  0x02 | payload... | XOR(payload) | 0x03
// The phone polls every 200 ms. It sends the next queued command, or SYS_STATUS when the queue is empty.

export const CMD = { INFO: 0x50, STATUS: 0x51, DATA: 0x52, CONTROL: 0x53, KEY: 0x54 };

export const STATE = {
  NORMAL: 0, END: 1, START: 2, RUNNING: 3, STOPPING: 4, ERROR: 5, SAFETY: 6, STUDY: 7,
};

export const STATE_NAME = {
  0: 'Ready', 1: 'Finished', 2: 'Starting', 3: 'Running', 4: 'Stopping',
  5: 'Error', 6: 'Safety key off', 7: 'Incline calibration',
};

// Error codes from the original app (English + more detailed Chinese strings).
export const ERRORS = {
  0: 'Safety switch is off',
  1: 'System communication error',
  2: 'Lower controller stall protection',
  3: 'Speed sensor failure',
  4: 'Incline calibration failed',
  5: 'Motor overload protection',
  6: 'Motor open-circuit protection',
  7: 'Motor over-voltage protection',
  8: 'Lower controller hardware fault',
  9: 'Treadmill is not powered on',
  10: 'Motor short-circuit protection',
  11: 'External AC over-voltage',
  12: 'Lower controller system fault',
  13: 'System communication error',
};

export function frame(payload) {
  const out = new Uint8Array(payload.length + 3);
  let x = 0;
  out[0] = 0x02;
  payload.forEach((b, i) => { out[i + 1] = b & 0xff; x ^= b & 0xff; });
  out[payload.length + 1] = x;
  out[payload.length + 2] = 0x03;
  return out;
}

// Returns the payload (without 0x02, checksum, 0x03), or null if the frame is invalid.
export function unframe(bytes) {
  const n = bytes.length;
  if (n < 4 || bytes[0] !== 0x02 || bytes[n - 1] !== 0x03) return null;
  let x = 0;
  for (let i = 1; i < n - 2; i++) x ^= bytes[i];
  if (x !== bytes[n - 2]) return null;
  return bytes.slice(1, n - 2);
}

const u16 = (p, i) => p[i] | (p[i + 1] << 8);
const u32 = (p, i) => (p[i] | (p[i + 1] << 8) | (p[i + 2] << 16) | (p[i + 3] << 24)) >>> 0;

// ---- commands (payloads) ----
export const cmd = {
  status: () => [CMD.STATUS],
  // Quick start in normal mode (the original app always sent 9 bytes, all zero after the sub-command).
  start: () => [CMD.CONTROL, 0x01, 0, 0, 0, 0, 0, 0, 0],
  stop: () => [CMD.CONTROL, 0x03],
  // speed in 0.1 units (e.g. 65 = 6.5 km/h)
  speed: (raw) => [CMD.CONTROL, 0x02, raw],
  speedIncline: (rawSpeed, incline) => [CMD.CONTROL, 0x02, rawSpeed, incline],
  // User id (uint32 LE) + body weight in kg. Sent on connect by the original app when logged in.
  user: (uid, weightKg) => [CMD.CONTROL, 0x00, uid & 0xff, (uid >> 8) & 0xff, (uid >> 16) & 0xff, (uid >>> 24) & 0xff, weightKg],
  // Model request; also sets the treadmill clock.
  infoModel: (d = new Date()) => [CMD.INFO, 0x00, d.getFullYear() - 2000, d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()],
  infoDate: () => [CMD.INFO, 0x01],
  infoSpeed: () => [CMD.INFO, 0x02],
  infoIncline: () => [CMD.INFO, 0x03],
  infoTotal: () => [CMD.INFO, 0x04],
  sportSummary: () => [CMD.DATA, 0x00],
};

// ---- parse a payload into an event object ----
export function parse(p) {
  const c = p[0];
  if (c === CMD.STATUS) {
    const state = p[1];
    const ev = { type: 'status', state };
    if (state === STATE.START) ev.countdown = p[2];
    if (state === STATE.ERROR) ev.error = p[2];
    if ((state === STATE.END || state === STATE.RUNNING || state === STATE.STOPPING) && p.length >= 13) {
      ev.live = {
        speed: p[2],          // 0.1 km/h (or mph if the treadmill unit is imperial)
        incline: p[3],
        time: u16(p, 4),      // seconds
        distance: u16(p, 6),  // metres
        calories: u16(p, 8),  // 0.1 kcal
        steps: u16(p, 10),
        heartRate: p[12],
      };
    }
    return ev;
  }
  if (c === CMD.INFO) {
    const sub = p[1];
    if (sub === 0x00) {
      const ev = { type: 'info', sub: 'model' };
      if (p.length === 9 || p.length === 5) {
        const bc = p[3] | (p[4] << 8);
        ev.model = (p[2] << 16) + bc;
        ev.modelName = p[2].toString(16) + '-' + bc;
        if (p.length === 9) ev.serial = u32(p, 5);
      }
      return ev;
    }
    if (sub === 0x01) return { type: 'info', sub: 'date', date: p.length === 5 ? `${2000 + p[2]}-${String(p[3]).padStart(2, '0')}-${String(p[4]).padStart(2, '0')}` : null };
    if (sub === 0x02) return { type: 'info', sub: 'speed', maxSpeed: p[2], minSpeed: p[3], unit: p.length > 4 ? p[4] : 0 };
    if (sub === 0x03) return { type: 'info', sub: 'incline', maxIncline: p.length === 4 ? p[2] : 0, minIncline: p.length === 4 ? p[3] : 0 };
    if (sub === 0x04) return { type: 'info', sub: 'total', total: p.length === 6 ? u32(p, 2) : null };
    return { type: 'info', sub };
  }
  if (c === CMD.DATA) {
    if (p[1] === 0x00 && p.length >= 10) {
      return { type: 'summary', time: u16(p, 2), distance: u16(p, 4), calories: u16(p, 6), steps: u16(p, 8) };
    }
    if (p[1] === 0x01 && p.length > 10) return { type: 'runway', runWay: p[10] };
    return { type: 'data', sub: p[1] };
  }
  if (c === CMD.CONTROL) return { type: 'control', sub: p[1] };
  return { type: 'unknown', cmd: c };
}

export const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(' ');
