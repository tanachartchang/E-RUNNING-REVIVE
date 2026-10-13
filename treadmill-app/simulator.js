// A fake treadmill that speaks the same protocol, for trying the app without hardware.
import { CMD, STATE, frame, unframe } from './protocol.js';

const le16 = (v) => [v & 0xff, (v >> 8) & 0xff];

export class Simulator {
  constructor() {
    this.onFrame = null;
    this.state = STATE.NORMAL;
    this.minSpeed = 10; this.maxSpeed = 120; this.maxIncline = 12;
    this.reset();
    this.clock = setInterval(() => this.second(), 1000);
  }

  reset() {
    this.speed = 0; this.target = 0; this.incline = 0;
    this.time = 0; this.dist = 0; this.cal = 0; this.steps = 0; this.hr = 0; this.countdown = 0;
    this.distAcc = 0; this.calAcc = 0; this.stepAcc = 0;
  }

  stop() { clearInterval(this.clock); }
  reply(payload) { setTimeout(() => this.onFrame && this.onFrame(frame(payload)), 20); }

  receive(bytes) {
    const p = unframe(bytes);
    if (!p) return;
    switch (p[0]) {
      case CMD.STATUS: return this.reply(this.statusPayload());
      case CMD.INFO:
        if (p[1] === 0) return this.reply([CMD.INFO, 0, 0x1a, ...le16(301), 0x39, 0x30, 0, 0]);
        if (p[1] === 1) return this.reply([CMD.INFO, 1, 15, 11, 20]);
        if (p[1] === 2) return this.reply([CMD.INFO, 2, this.maxSpeed, this.minSpeed, 0]);
        if (p[1] === 3) return this.reply([CMD.INFO, 3, this.maxIncline, 0]);
        if (p[1] === 4) return this.reply([CMD.INFO, 4, 0x2c, 0x01, 0, 0]);
        return;
      case CMD.DATA:
        if (p[1] === 0) return this.reply([CMD.DATA, 0, ...le16(this.time), ...le16(this.dist), ...le16(this.cal), ...le16(this.steps)]);
        return this.reply([CMD.DATA, p[1]]);
      case CMD.CONTROL:
        if (p[1] === 1 && this.state === STATE.NORMAL) { this.reset(); this.state = STATE.START; this.countdown = 3; }
        if (p[1] === 3 && (this.state === STATE.RUNNING || this.state === STATE.START)) { this.state = STATE.STOPPING; this.target = 0; }
        if (p[1] === 2 && this.state === STATE.RUNNING) {
          this.target = Math.min(this.maxSpeed, Math.max(this.minSpeed, p[2]));
          if (p.length > 3) this.incline = Math.min(this.maxIncline, p[3]);
        }
        return this.reply([CMD.CONTROL, p[1]]);
    }
  }

  statusPayload() {
    const s = this.state;
    if (s === STATE.START) return [CMD.STATUS, s, this.countdown];
    if (s === STATE.RUNNING || s === STATE.STOPPING || s === STATE.END) {
      return [CMD.STATUS, s, this.speed, this.incline, ...le16(this.time), ...le16(this.dist), ...le16(this.cal), ...le16(this.steps), this.hr, 0];
    }
    return [CMD.STATUS, s];
  }

  second() {
    if (this.state === STATE.START) {
      if (--this.countdown <= 0) { this.state = STATE.RUNNING; this.speed = this.target = this.minSpeed; }
      return;
    }
    if (this.state === STATE.END) { this.state = STATE.NORMAL; return; }
    if (this.state !== STATE.RUNNING && this.state !== STATE.STOPPING) return;
    if (this.speed < this.target) this.speed = Math.min(this.target, this.speed + 5);
    if (this.speed > this.target) this.speed = Math.max(this.target, this.speed - (this.state === STATE.STOPPING ? 30 : 5));
    if (this.state === STATE.STOPPING && this.speed === 0) { this.state = STATE.END; return; }
    const kmh = this.speed / 10;
    this.time++;
    this.distAcc += kmh / 3.6;
    this.calAcc += kmh * (1 + this.incline / 10) * 0.3;
    this.stepAcc += kmh < 6 ? 1.8 : 2.7;
    this.dist = Math.floor(this.distAcc);
    this.cal = Math.floor(this.calAcc);
    this.steps = Math.floor(this.stepAcc);
    this.hr = Math.round(80 + kmh * 7 + this.incline * 2);
  }
}
