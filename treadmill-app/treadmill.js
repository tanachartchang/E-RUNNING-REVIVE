// Connection + polling loop. Behaves like CommuacateServiceBluetooth in the original app:
// every 200 ms send the head of the command queue (max 3 tries) or a status poll;
// a reply whose command byte matches the queued command acknowledges it.
import { CMD, frame, unframe, parse, cmd, hex } from './protocol.js';

// The original app took "the 4th GATT service, characteristic 0 = notify, 1 = write" without naming UUIDs.
// Web Bluetooth must declare services up front, so list the UUIDs these BLE-UART modules commonly use.
export const CANDIDATE_SERVICES = [
  0xfff0, 0xffe0, 0xffe5, 0xff00, 0xffb0, 0xffc0, 0xffd0, 0xffa0, 0xfee7, 0xae00, 0xae30, 0xfe60,
  '49535343-fe7d-4ae5-8fa9-9fafd205e455', // Microchip/ISSC transparent UART
  '6e400001-b5a3-f393-e0a9-e50e24dcca9e', // Nordic UART
  '0000fff0-0000-1000-8000-00805f9b34fb',
];

const isStandardService = (uuid) => /^000018[0-9a-f]{2}-0000-1000-8000-00805f9b34fb$/i.test(uuid);

export class Treadmill extends EventTarget {
  constructor() {
    super();
    this.queue = [];
    this.timer = null;
    this.busy = false;
    this.rx = [];
    this.link = null; // { write(bytes), close() }
  }

  emit(type, detail) { this.dispatchEvent(new CustomEvent(type, { detail })); }
  log(dir, bytes) { this.emit('log', { dir, text: hex(bytes) }); }

  get connected() { return !!this.link; }

  async connectBluetooth(extraService) {
    if (!navigator.bluetooth) throw new Error('Web Bluetooth is not available. Use Chrome on Android, Windows, macOS or ChromeOS.');
    const optionalServices = [...CANDIDATE_SERVICES];
    if (extraService) optionalServices.push(/^[0-9a-f]{4}$/i.test(extraService) ? parseInt(extraService, 16) : extraService.toLowerCase());
    const device = await navigator.bluetooth.requestDevice({ acceptAllDevices: true, optionalServices });
    this.emit('connecting', { name: device.name || 'Treadmill' });
    const server = await device.gatt.connect();
    const { notifyChar, writeChar, service } = await this.findChannel(server);
    this.emit('log', { dir: 'i', text: `service ${service.uuid}, notify ${notifyChar.uuid}, write ${writeChar.uuid}` });

    notifyChar.addEventListener('characteristicvaluechanged', (e) => {
      this.onBytes(new Uint8Array(e.target.value.buffer, e.target.value.byteOffset, e.target.value.byteLength));
    });
    await notifyChar.startNotifications();

    const noResp = writeChar.properties.writeWithoutResponse;
    device.addEventListener('gattserverdisconnected', () => this.onDisconnected());
    this.link = {
      name: device.name || 'Treadmill',
      write: (bytes) => (noResp ? writeChar.writeValueWithoutResponse(bytes) : writeChar.writeValueWithResponse(bytes)),
      close: () => { if (device.gatt.connected) device.gatt.disconnect(); },
    };
    this.onConnected();
  }

  async findChannel(server) {
    const services = await server.getPrimaryServices();
    const custom = services.filter((s) => !isStandardService(s.uuid));
    for (const service of custom) {
      const chars = await service.getCharacteristics();
      const notifyChar = chars.find((c) => c.properties.notify || c.properties.indicate);
      if (!notifyChar) continue;
      const writeChar = chars.find((c) => c !== notifyChar && (c.properties.writeWithoutResponse || c.properties.write))
        || chars.find((c) => c.properties.writeWithoutResponse || c.properties.write);
      if (writeChar) return { service, notifyChar, writeChar };
    }
    server.disconnect();
    throw new Error('Connected, but no treadmill data channel was found. Note the service UUID with an app like nRF Connect and enter it under Settings → Custom service UUID.');
  }

  connectSimulator(sim) {
    sim.onFrame = (bytes) => this.onBytes(bytes);
    this.link = { name: 'Demo treadmill', write: async (bytes) => sim.receive(bytes), close: () => sim.stop() };
    this.emit('connecting', { name: this.link.name });
    this.onConnected();
  }

  onConnected() {
    this.queue = [];
    this.emit('connected', { name: this.link.name });
    this.timer = setInterval(() => this.tick(), 200);
  }

  onDisconnected() {
    clearInterval(this.timer);
    this.timer = null;
    this.link = null;
    this.queue = [];
    this.emit('disconnected');
  }

  disconnect() {
    const link = this.link;
    this.onDisconnected();
    if (link) link.close();
  }

  send(payload) { if (this.link) this.queue.push({ data: payload, tries: 0 }); }

  async tick() {
    if (!this.link || this.busy) return;
    let payload;
    while (this.queue.length && this.queue[0].tries >= 3) this.queue.shift();
    if (this.queue.length) { this.queue[0].tries++; payload = this.queue[0].data; }
    else payload = cmd.status();
    const bytes = frame(payload);
    this.busy = true;
    try {
      await this.link.write(bytes);
      if (payload[0] !== CMD.STATUS) this.log('>', bytes);
    } catch (e) {
      this.emit('log', { dir: '!', text: e.message });
    } finally {
      this.busy = false;
    }
  }

  onBytes(chunk) {
    // Normally one notification = one frame; reassemble in case a module splits it.
    this.rx = this.rx.length ? [...this.rx, ...chunk] : [...chunk];
    if (this.rx[0] !== 0x02 || this.rx.length > 64) { this.rx = []; return; }
    const p = unframe(Uint8Array.from(this.rx));
    if (!p) return; // wait for the rest
    const bytes = this.rx;
    this.rx = [];
    if (p[0] !== CMD.STATUS) this.log('<', bytes);
    if (this.queue.length && p[0] !== CMD.STATUS && p[0] === this.queue[0].data[0]) this.queue.shift();
    this.emit('message', parse(p));
  }
}
