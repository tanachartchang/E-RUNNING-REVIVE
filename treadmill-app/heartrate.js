// Standard Bluetooth Heart Rate Service (0x180D). Works with watches that broadcast heart rate
// (e.g. Huawei Watch GT 2 Pro / GT 3 / GT 4 and later) and chest straps.
export class HeartRateMonitor extends EventTarget {
  constructor() { super(); this.device = null; }

  get connected() { return !!(this.device && this.device.gatt.connected); }

  async connect() {
    if (!navigator.bluetooth) throw new Error('Web Bluetooth is not available in this browser.');
    this.device = await navigator.bluetooth.requestDevice({ filters: [{ services: ['heart_rate'] }] });
    this.device.addEventListener('gattserverdisconnected', () => this.dispatchEvent(new Event('disconnected')));
    const server = await this.device.gatt.connect();
    const service = await server.getPrimaryService('heart_rate');
    const ch = await service.getCharacteristic('heart_rate_measurement');
    ch.addEventListener('characteristicvaluechanged', (e) => {
      const v = e.target.value;
      const bpm = v.getUint8(0) & 0x01 ? v.getUint16(1, true) : v.getUint8(1);
      this.dispatchEvent(new CustomEvent('bpm', { detail: bpm }));
    });
    await ch.startNotifications();
    return this.device.name || 'Heart-rate monitor';
  }

  disconnect() { if (this.connected) this.device.gatt.disconnect(); }
}
