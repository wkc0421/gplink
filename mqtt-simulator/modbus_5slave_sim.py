#!/usr/bin/env python3
"""
Modbus RTU-over-TCP 5从机模拟器（无第三方依赖，仅用 stdlib）
用于 GPLink/JetLinks 平台 Modbus 接入端到端测试

从机布局:
  Slave 1 (id=1): 温湿度传感器
      FC03 addr=0  temperature  INT16   ×0.1  °C
      FC03 addr=1  humidity     INT16   ×0.1  %RH

  Slave 2 (id=2): 压力流量变送器
      FC03 addr=0-1  pressure   FLOAT32 ABCD  kPa
      FC03 addr=2-3  flow_rate  FLOAT32 ABCD  m³/h

  Slave 3 (id=3): 电能表
      FC03 addr=0-1  voltage      FLOAT32 ABCD  V
      FC03 addr=2-3  current      FLOAT32 ABCD  A
      FC03 addr=4-5  active_power FLOAT32 ABCD  kW
      FC03 addr=6-7  energy       FLOAT32 ABCD  kWh（累计，只增不减）

  Slave 4 (id=4): 继电器控制器
      FC01 addr=0  relay1  BIT  可写(FC05)
      FC01 addr=1  relay2  BIT  可写(FC05)
      FC01 addr=2  relay3  BIT  可写(FC05)
      FC01 addr=3  relay4  BIT  可写(FC05)
      FC03 addr=0  supply_voltage  UINT16  ×0.1  V

  Slave 5 (id=5): 变频器
      FC03 addr=0-1  output_freq     FLOAT32 ABCD  Hz
      FC03 addr=2-3  output_voltage  FLOAT32 ABCD  V
      FC03 addr=4-5  output_current  FLOAT32 ABCD  A
      FC03 addr=6    fault_code      UINT16        (0=正常)
      FC01 addr=0    running_status  BIT   可写(FC05)

用法:
  python modbus_5slave_sim.py [--port 4000] [--host 0.0.0.0]

配套平台配置: 见 modbus-fixtures/ 目录
"""

import socket
import struct
import threading
import time
import random
import math
import argparse
import sys

# ── CRC16-IBM（Modbus RTU 标准）────────────────────────────────────────────

def crc16(data: bytes) -> bytes:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if (crc & 1) else (crc >> 1)
    return bytes([crc & 0xFF, (crc >> 8) & 0xFF])

def with_crc(data: bytes) -> bytes:
    return data + crc16(data)

def check_crc(frame: bytes) -> bool:
    return len(frame) >= 4 and crc16(frame[:-2]) == frame[-2:]

# ── FLOAT32 ABCD 打包/解包 ──────────────────────────────────────────────────

def pack_f32(f: float) -> list:
    """float → [high_word, low_word] (ABCD big-endian)"""
    b = struct.pack('>f', float(f))
    return [(b[0] << 8) | b[1], (b[2] << 8) | b[3]]

# ── 从机数据存储 ────────────────────────────────────────────────────────────

class SlaveState:
    def __init__(self, hr: list, co: list = None):
        self.hr = hr   # 保持寄存器（UINT16 列表）
        self.co = co or []  # 线圈（bool 列表）
        self._lock = threading.Lock()

    def get_hr(self, start: int, qty: int) -> list:
        with self._lock:
            return [self.hr[start + i] if (start + i) < len(self.hr) else 0
                    for i in range(qty)]

    def set_hr(self, start: int, values: list):
        with self._lock:
            for i, v in enumerate(values):
                if start + i < len(self.hr):
                    self.hr[start + i] = int(v) & 0xFFFF

    def get_co(self, start: int, qty: int) -> list:
        with self._lock:
            return [self.co[start + i] if (start + i) < len(self.co) else False
                    for i in range(qty)]

    def set_co(self, addr: int, on: bool):
        with self._lock:
            if addr < len(self.co):
                self.co[addr] = on

slaves = {
    1: SlaveState(hr=[253, 600]),               # temp×10, hum×10
    2: SlaveState(hr=[0]*4),                    # FLOAT32×2
    3: SlaveState(hr=[0]*8),                    # FLOAT32×4
    4: SlaveState(hr=[2200], co=[False]*4),     # supply_voltage×10; 4 relays
    5: SlaveState(hr=[0]*7, co=[False]),        # FLOAT32×3 + fault; running
}

def _init():
    slaves[2].set_hr(0, pack_f32(150.0) + pack_f32(30.0))
    slaves[3].set_hr(0, pack_f32(220.0) + pack_f32(10.0) + pack_f32(2.2) + pack_f32(1000.0))
    slaves[5].set_hr(0, pack_f32(0.0) + pack_f32(0.0) + pack_f32(0.0) + [0])

_init()

# ── 数据动态更新线程 ────────────────────────────────────────────────────────

def _data_loop():
    energy = 1000.0
    t = 0
    while True:
        time.sleep(3)
        t += 3

        # Slave 1: 温湿度正弦波动
        slaves[1].set_hr(0, [
            int(250 + 50 * math.sin(t / 30) + random.uniform(-5, 5)),
            int(600 + 100 * math.sin(t / 60) + random.uniform(-10, 10)),
        ])

        # Slave 2: 压力流量波动
        slaves[2].set_hr(0,
            pack_f32(150 + 30 * math.sin(t / 40) + random.uniform(-5, 5)) +
            pack_f32(30 + 10 * math.sin(t / 25) + random.uniform(-2, 2))
        )

        # Slave 3: 电气量波动 + 能量累积
        v = 220 + random.uniform(-3, 3)
        i = 10 + 5 * abs(math.sin(t / 20)) + random.uniform(-0.5, 0.5)
        p = v * i / 1000
        energy += p * 3 / 3600
        slaves[3].set_hr(0,
            pack_f32(v) + pack_f32(i) + pack_f32(p) + pack_f32(energy)
        )

        # Slave 5: 变频器——运行状态决定输出值
        running = slaves[5].get_co(0, 1)[0]
        if running:
            freq = 48 + 2 * math.sin(t / 15) + random.uniform(-0.5, 0.5)
            vout = 370 + random.uniform(-5, 5)
            cout = 18 + random.uniform(-2, 2)
        else:
            freq, vout, cout = 0.0, 0.0, 0.0
        slaves[5].set_hr(0, pack_f32(freq) + pack_f32(vout) + pack_f32(cout) + [0])

threading.Thread(target=_data_loop, daemon=True).start()

# ── 请求处理 ────────────────────────────────────────────────────────────────

def resp_fc01(sid, start, qty) -> bytes:
    bits = slaves[sid].get_co(start, qty)
    n = (qty + 7) // 8
    out = [0] * n
    for i, b in enumerate(bits):
        if b:
            out[i // 8] |= (1 << (i % 8))
    return with_crc(bytes([sid, 0x01, n] + out))

def resp_fc03(sid, start, qty) -> bytes:
    regs = slaves[sid].get_hr(start, qty)
    data = b''.join(struct.pack('>H', r & 0xFFFF) for r in regs)
    return with_crc(bytes([sid, 0x03, len(data)]) + data)

def resp_fc05(sid, addr, raw_val) -> bytes:
    on = (raw_val == 0xFF00)
    slaves[sid].set_co(addr, on)
    label = {(4,0):"relay1",(4,1):"relay2",(4,2):"relay3",(4,3):"relay4",
             (5,0):"running_status"}.get((sid, addr), f"co[{addr}]")
    print(f"    [Slave {sid}] WRITE {label} → {'ON' if on else 'OFF'}")
    return with_crc(bytes([sid, 0x05, addr >> 8, addr & 0xFF, 0xFF if on else 0x00, 0x00]))

def resp_fc06(sid, addr, val) -> bytes:
    slaves[sid].set_hr(addr, [val])
    print(f"    [Slave {sid}] WRITE hr[{addr}] ← {val}")
    return with_crc(bytes([sid, 0x06, addr >> 8, addr & 0xFF, val >> 8, val & 0xFF]))

def resp_err(sid, fc, code) -> bytes:
    return with_crc(bytes([sid, fc | 0x80, code]))

FC_NAMES = {0x01:"ReadCoils", 0x03:"ReadHoldingRegs", 0x05:"WriteSingleCoil", 0x06:"WriteSingleReg"}

def process(frame: bytes):
    if len(frame) < 8 or not check_crc(frame):
        return None
    sid  = frame[0]
    fc   = frame[1]
    addr = (frame[2] << 8) | frame[3]
    qty  = (frame[4] << 8) | frame[5]

    if sid not in slaves:
        return None  # 未知从机，忽略

    print(f"  ← slave={sid} {FC_NAMES.get(fc, f'FC0x{fc:02X}')} addr={addr} qty/val={qty}")

    if   fc == 0x01: return resp_fc01(sid, addr, qty)
    elif fc == 0x03: return resp_fc03(sid, addr, qty)
    elif fc == 0x05: return resp_fc05(sid, addr, qty)
    elif fc == 0x06: return resp_fc06(sid, addr, qty)
    else:            return resp_err(sid, fc, 0x01)  # Illegal Function

# ── 连接处理 ─────────────────────────────────────────────────────────────────

def handle(conn: socket.socket, peer):
    print(f"[+] 连接来自 {peer}")
    buf = b""
    try:
        while True:
            chunk = conn.recv(256)
            if not chunk:
                break
            buf += chunk
            while len(buf) >= 8:
                resp = process(buf[:8])
                buf = buf[8:]
                if resp:
                    conn.sendall(resp)
    except Exception as e:
        print(f"[!] {peer}: {e}")
    finally:
        conn.close()
        print(f"[-] 断开: {peer}")

# ── 主程序 ────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="Modbus 5从机模拟器")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=4000)
    args = ap.parse_args()

    print("=" * 62)
    print(" Modbus RTU-over-TCP 5从机模拟器")
    print(f" 监听: {args.host}:{args.port}")
    print("=" * 62)
    print(" Slave 1  温湿度传感器    FC03 addr=0(温度) addr=1(湿度)")
    print(" Slave 2  压力流量变送器  FC03 addr=0-1(压力,FLOAT32) addr=2-3(流量,FLOAT32)")
    print(" Slave 3  电能表          FC03 addr=0-7(电压/电流/功率/电能,FLOAT32×4)")
    print(" Slave 4  继电器控制器    FC01 addr=0-3(relay1-4) FC03 addr=0(供电电压)")
    print(" Slave 5  变频器          FC03 addr=0-6(频率/电压/电流/故障) FC01 addr=0(运行状态)")
    print("=" * 62)
    print()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((args.host, args.port))
    except OSError as e:
        print(f"绑定失败: {e}", file=sys.stderr)
        sys.exit(1)
    srv.listen(5)
    print(f"等待平台连接 (TCP Server 模式)…\n")

    while True:
        conn, peer = srv.accept()
        threading.Thread(target=handle, args=(conn, peer), daemon=True).start()

if __name__ == "__main__":
    main()
