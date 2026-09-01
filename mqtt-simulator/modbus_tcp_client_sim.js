#!/usr/bin/env node
'use strict';

const net = require('net');

const DEVICE_TYPES = [
  { key: 'temp', productId: 'modbus-slave-temp', name: 'temperature-humidity' },
  { key: 'pressure', productId: 'modbus-slave-pressure', name: 'pressure-flow' },
  { key: 'meter', productId: 'modbus-slave-meter', name: 'power-meter' },
  { key: 'relay', productId: 'modbus-slave-relay', name: 'relay-controller' },
  { key: 'vfd', productId: 'modbus-slave-vfd', name: 'vfd' },
];

const FC_NAMES = {
  1: 'ReadCoils',
  2: 'ReadDiscreteInputs',
  3: 'ReadHoldingRegisters',
  4: 'ReadInputRegisters',
  5: 'WriteSingleCoil',
  6: 'WriteSingleRegister',
  15: 'WriteMultipleCoils',
  16: 'WriteMultipleRegisters',
};

const DEFAULTS = {
  host: '127.0.0.1',
  port: 4000,
  gateways: 5,
  slavesPerGateway: 20,
  gatewayPrefix: 'mb_400',
  gatewayStart: 0,
  reconnectMs: 3000,
  sourcePortStart: 0,
  verbose: false,
};

function crc16(data) {
  let crc = 0xFFFF;
  for (const byte of data) {
    crc ^= byte;
    for (let i = 0; i < 8; i += 1) {
      crc = (crc & 1) ? ((crc >> 1) ^ 0xA001) : (crc >> 1);
    }
  }
  return Buffer.from([crc & 0xFF, (crc >> 8) & 0xFF]);
}

function withCrc(body) {
  return Buffer.concat([body, crc16(body)]);
}

function validCrc(frame) {
  if (frame.length < 4) return false;
  const expected = crc16(frame.subarray(0, frame.length - 2));
  return expected[0] === frame[frame.length - 2] && expected[1] === frame[frame.length - 1];
}

function packF32(value) {
  const buf = Buffer.allocUnsafe(4);
  buf.writeFloatBE(Number(value), 0);
  return [buf.readUInt16BE(0), buf.readUInt16BE(2)];
}

function clampU16(value) {
  return Math.max(0, Math.min(0xFFFF, Math.round(value))) & 0xFFFF;
}

function requestFrameLength(buffer) {
  if (buffer.length < 2) return 0;
  const fc = buffer[1];
  if (fc === 1 || fc === 2 || fc === 3 || fc === 4 || fc === 5 || fc === 6) {
    return 8;
  }
  if (fc === 15 || fc === 16) {
    if (buffer.length < 7) return 0;
    return 9 + buffer[6];
  }
  return 8;
}

function hex(buffer) {
  return buffer.toString('hex').replace(/(..)/g, '$1 ').trim().toUpperCase();
}

class SlaveState {
  constructor(slaveId, gatewayIndex) {
    this.slaveId = slaveId;
    this.gatewayIndex = gatewayIndex;
    this.templateIndex = (slaveId - 1) % DEVICE_TYPES.length;
    this.type = DEVICE_TYPES[this.templateIndex].key;
    this.hr = Array(64).fill(0);
    this.co = Array(64).fill(false);
    this.energy = 1000 + gatewayIndex * 100 + slaveId;
    this.phase = gatewayIndex * 0.7 + slaveId * 0.13;
    this.update(0);
  }

  update(tick) {
    const t = tick + this.phase;
    const gw = this.gatewayIndex + 1;
    switch (this.type) {
      case 'temp':
        this.hr[0] = clampU16(245 + gw * 4 + 45 * Math.sin(t / 10));
        this.hr[1] = clampU16(580 + gw * 8 + 90 * Math.sin(t / 18));
        break;
      case 'pressure': {
        const pressure = 135 + gw * 3 + 28 * Math.sin(t / 12);
        const flow = 24 + gw + 9 * Math.sin(t / 8);
        this.setHr(0, [...packF32(pressure), ...packF32(flow)]);
        break;
      }
      case 'meter': {
        const voltage = 218 + gw + 3 * Math.sin(t / 8);
        const current = 8 + gw * 0.4 + 4 * Math.abs(Math.sin(t / 7));
        const power = voltage * current / 1000;
        this.energy += power * 3 / 3600;
        this.setHr(0, [
          ...packF32(voltage),
          ...packF32(current),
          ...packF32(power),
          ...packF32(this.energy),
        ]);
        break;
      }
      case 'relay':
        this.hr[0] = clampU16(2190 + gw * 6 + 8 * Math.sin(t / 9));
        break;
      case 'vfd': {
        const running = this.co[0];
        const freq = running ? 47 + gw * 0.5 + 2 * Math.sin(t / 5) : 0;
        const voltage = running ? 365 + gw * 2 + 5 * Math.sin(t / 6) : 0;
        const current = running ? 15 + gw * 0.8 + 2 * Math.sin(t / 4) : 0;
        this.setHr(0, [...packF32(freq), ...packF32(voltage), ...packF32(current), 0]);
        break;
      }
      default:
        break;
    }
  }

  getHr(start, quantity) {
    return Array.from({ length: quantity }, (_, index) => this.hr[start + index] || 0);
  }

  setHr(start, values) {
    values.forEach((value, index) => {
      const addr = start + index;
      if (addr >= 0 && addr < this.hr.length) {
        this.hr[addr] = clampU16(value);
      }
    });
  }

  getCo(start, quantity) {
    return Array.from({ length: quantity }, (_, index) => !!this.co[start + index]);
  }

  setCo(start, values) {
    values.forEach((value, index) => {
      const addr = start + index;
      if (addr >= 0 && addr < this.co.length) {
        this.co[addr] = !!value;
      }
    });
  }
}

class GatewayBus {
  constructor(gatewayId, gatewayIndex, slavesPerGateway, verbose) {
    this.gatewayId = gatewayId;
    this.verbose = verbose;
    this.tick = 0;
    this.slaves = new Map();
    for (let slaveId = 1; slaveId <= slavesPerGateway; slaveId += 1) {
      this.slaves.set(slaveId, new SlaveState(slaveId, gatewayIndex));
    }
    this.timer = setInterval(() => this.update(), 3000);
  }

  stop() {
    clearInterval(this.timer);
  }

  update() {
    this.tick += 3;
    for (const slave of this.slaves.values()) {
      slave.update(this.tick);
    }
  }

  process(frame) {
    const slaveId = frame[0];
    const fc = frame[1];
    const slave = this.slaves.get(slaveId);
    if (!slave) {
      if (this.verbose) {
        console.log(`[${this.gatewayId}] ignore unknown slave=${slaveId} frame=${hex(frame)}`);
      }
      return null;
    }

    const address = frame.readUInt16BE(2);
    const valueOrQuantity = frame.readUInt16BE(4);
    if (this.verbose) {
      console.log(`[${this.gatewayId}] <- slave=${slaveId} ${FC_NAMES[fc] || `FC${fc}`} addr=${address} value/qty=${valueOrQuantity}`);
    }

    switch (fc) {
      case 1:
      case 2:
        return this.readBits(slave, fc, address, valueOrQuantity);
      case 3:
      case 4:
        return this.readRegisters(slave, fc, address, valueOrQuantity);
      case 5:
        return this.writeSingleCoil(slave, frame, address, valueOrQuantity);
      case 6:
        return this.writeSingleRegister(slave, frame, address, valueOrQuantity);
      case 15:
        return this.writeMultipleCoils(slave, frame, address, valueOrQuantity);
      case 16:
        return this.writeMultipleRegisters(slave, frame, address, valueOrQuantity);
      default:
        return exceptionFrame(slaveId, fc, 1);
    }
  }

  readBits(slave, fc, start, quantity) {
    if (quantity < 1 || quantity > 2000) return exceptionFrame(slave.slaveId, fc, 3);
    const bits = slave.getCo(start, quantity);
    const bytes = Buffer.alloc(Math.ceil(quantity / 8));
    bits.forEach((on, index) => {
      if (on) bytes[Math.floor(index / 8)] |= (1 << (index % 8));
    });
    return withCrc(Buffer.concat([Buffer.from([slave.slaveId, fc, bytes.length]), bytes]));
  }

  readRegisters(slave, fc, start, quantity) {
    if (quantity < 1 || quantity > 125) return exceptionFrame(slave.slaveId, fc, 3);
    const values = slave.getHr(start, quantity);
    const body = Buffer.alloc(3 + values.length * 2);
    body[0] = slave.slaveId;
    body[1] = fc;
    body[2] = values.length * 2;
    values.forEach((value, index) => body.writeUInt16BE(value & 0xFFFF, 3 + index * 2));
    return withCrc(body);
  }

  writeSingleCoil(slave, frame, address, rawValue) {
    if (rawValue !== 0x0000 && rawValue !== 0xFF00) {
      return exceptionFrame(slave.slaveId, 5, 3);
    }
    slave.setCo(address, [rawValue === 0xFF00]);
    return withCrc(frame.subarray(0, 6));
  }

  writeSingleRegister(slave, frame, address, value) {
    slave.setHr(address, [value]);
    return withCrc(frame.subarray(0, 6));
  }

  writeMultipleCoils(slave, frame, address, quantity) {
    if (quantity < 1 || quantity > 1968) return exceptionFrame(slave.slaveId, 15, 3);
    const byteCount = frame[6];
    if (byteCount < Math.ceil(quantity / 8)) return exceptionFrame(slave.slaveId, 15, 3);
    const values = [];
    for (let index = 0; index < quantity; index += 1) {
      const b = frame[7 + Math.floor(index / 8)];
      values.push(((b >> (index % 8)) & 1) === 1);
    }
    slave.setCo(address, values);
    return withCrc(frame.subarray(0, 6));
  }

  writeMultipleRegisters(slave, frame, address, quantity) {
    if (quantity < 1 || quantity > 123) return exceptionFrame(slave.slaveId, 16, 3);
    const byteCount = frame[6];
    if (byteCount !== quantity * 2) return exceptionFrame(slave.slaveId, 16, 3);
    const values = [];
    for (let index = 0; index < quantity; index += 1) {
      values.push(frame.readUInt16BE(7 + index * 2));
    }
    slave.setHr(address, values);
    return withCrc(frame.subarray(0, 6));
  }
}

function exceptionFrame(slaveId, fc, code) {
  return withCrc(Buffer.from([slaveId, fc | 0x80, code]));
}

class GatewayClient {
  constructor(index, options) {
    this.index = index;
    this.options = options;
    this.gatewayId = `${options.gatewayPrefix}${options.gatewayStart + index}`;
    this.bus = new GatewayBus(this.gatewayId, index, options.slavesPerGateway, options.verbose);
    this.buffer = Buffer.alloc(0);
    this.socket = null;
    this.reconnectTimer = null;
    this.stopped = false;
  }

  start() {
    this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.reconnectTimer);
    this.bus.stop();
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
  }

  connect() {
    if (this.stopped) return;
    const socket = new net.Socket();
    socket.setNoDelay(true);
    this.socket = socket;
    this.buffer = Buffer.alloc(0);

    socket.on('connect', () => {
      const local = `${socket.localAddress}:${socket.localPort}`;
      console.log(`[${this.gatewayId}] connected to ${this.options.host}:${this.options.port} from ${local}`);
    });
    socket.on('data', chunk => this.onData(chunk));
    socket.on('error', err => {
      console.warn(`[${this.gatewayId}] socket error: ${err.message}`);
    });
    socket.on('close', () => {
      if (!this.stopped) {
        console.log(`[${this.gatewayId}] disconnected, reconnect in ${this.options.reconnectMs}ms`);
        this.reconnectTimer = setTimeout(() => this.connect(), this.options.reconnectMs);
      }
    });

    const connectOptions = {
      host: this.options.host,
      port: this.options.port,
    };
    if (this.options.sourcePortStart > 0) {
      connectOptions.localPort = this.options.sourcePortStart + this.index;
    }
    socket.connect(connectOptions);
  }

  onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2) {
      const length = requestFrameLength(this.buffer);
      if (length === 0 || this.buffer.length < length) return;
      const frame = this.buffer.subarray(0, length);
      if (!validCrc(frame)) {
        console.warn(`[${this.gatewayId}] drop byte while resyncing invalid CRC: ${hex(this.buffer.subarray(0, Math.min(this.buffer.length, length)))}`);
        this.buffer = this.buffer.subarray(1);
        continue;
      }
      this.buffer = this.buffer.subarray(length);
      const response = this.bus.process(frame);
      if (response && this.socket && !this.socket.destroyed) {
        if (this.options.verbose) {
          console.log(`[${this.gatewayId}] -> ${hex(response)}`);
        }
        this.socket.write(response);
      }
    }
  }
}

function parseArgs(argv) {
  const options = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
      continue;
    }
    if (arg === '--verbose') {
      options.verbose = true;
      continue;
    }
    if (!arg.startsWith('--')) {
      throw new Error(`Unexpected argument: ${arg}`);
    }
    const eq = arg.indexOf('=');
    const key = arg.slice(2, eq > 0 ? eq : undefined);
    const value = eq > 0 ? arg.slice(eq + 1) : argv[++i];
    if (value === undefined) throw new Error(`Missing value for --${key}`);
    switch (key) {
      case 'host':
        options.host = value;
        break;
      case 'port':
      case 'gateways':
      case 'slaves-per-gateway':
      case 'gateway-start':
      case 'reconnect-ms':
      case 'source-port-start': {
        const normalized = key.replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
        options[normalized] = Number(value);
        break;
      }
      case 'gateway-prefix':
        options.gatewayPrefix = value;
        break;
      default:
        throw new Error(`Unknown option: --${key}`);
    }
  }
  validateOptions(options);
  return options;
}

function validateOptions(options) {
  const ranges = [
    ['port', 1, 65535],
    ['gateways', 1, 200],
    ['slavesPerGateway', 1, 247],
    ['gatewayStart', 0, 999999],
    ['reconnectMs', 100, 3600000],
    ['sourcePortStart', 0, 65535],
  ];
  for (const [key, min, max] of ranges) {
    if (!Number.isInteger(options[key]) || options[key] < min || options[key] > max) {
      throw new Error(`--${key} must be an integer in [${min}, ${max}]`);
    }
  }
  if (options.sourcePortStart > 0 && options.sourcePortStart + options.gateways - 1 > 65535) {
    throw new Error('--source-port-start plus gateway count exceeds 65535');
  }
}

function printHelp() {
  console.log(`Modbus RTU-over-TCP multi-gateway TCP client simulator

Usage:
  node modbus_tcp_client_sim.js [options]

Options:
  --host <host>                 Platform TCP Server host, default ${DEFAULTS.host}
  --port <port>                 Platform TCP Server port, default ${DEFAULTS.port}
  --gateways <count>            TCP client connections, default ${DEFAULTS.gateways}
  --slaves-per-gateway <count>  Slave IDs per gateway, default ${DEFAULTS.slavesPerGateway}
  --gateway-prefix <prefix>     Gateway ID prefix for logs, default ${DEFAULTS.gatewayPrefix}
  --gateway-start <number>      First gateway suffix, default ${DEFAULTS.gatewayStart}
  --source-port-start <port>    Optional stable local port range, default disabled
  --reconnect-ms <ms>           Reconnect delay, default ${DEFAULTS.reconnectMs}
  --verbose                     Print request and response frames
  --help                        Show this help

Default device layout:
  5 gateways: mb_4000..mb_4004
  20 slaves per gateway: slaveId 1..20
  Device type repeats every 5 slave IDs:
    1=temp, 2=pressure, 3=meter, 4=relay, 5=vfd
`);
}

function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(err.message);
    console.error('Run with --help for usage.');
    process.exit(1);
  }
  if (options.help) {
    printHelp();
    return;
  }

  console.log('Modbus RTU-over-TCP TCP client simulator');
  console.log(`Target: ${options.host}:${options.port}`);
  console.log(`Gateways: ${options.gateways}, slaves per gateway: ${options.slavesPerGateway}`);
  console.log(`Gateway IDs: ${options.gatewayPrefix}${options.gatewayStart}..${options.gatewayPrefix}${options.gatewayStart + options.gateways - 1}`);

  const clients = Array.from({ length: options.gateways }, (_, index) => new GatewayClient(index, options));
  clients.forEach(client => client.start());

  const shutdown = () => {
    console.log('\nStopping simulator...');
    clients.forEach(client => client.stop());
    setTimeout(() => process.exit(0), 100);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (require.main === module) {
  main();
}

module.exports = {
  GatewayBus,
  crc16,
  withCrc,
  validCrc,
};
