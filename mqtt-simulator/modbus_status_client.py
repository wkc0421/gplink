#!/usr/bin/env python3
"""Respond to configured Modbus RTU status reads over an outgoing TCP connection."""

import argparse
import csv
import json
import os
from pathlib import Path
import signal
import socket
import struct
import time


def crc16(data):
    crc = 0xFFFF
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return struct.pack("<H", crc)


def adu(body):
    return body + crc16(body)


def load_devices(profiles, status):
    devices = {}
    for device_file, mapping_file in profiles:
        with open(mapping_file, encoding="utf-8-sig", newline="") as source:
            mappings = list(csv.DictReader(source))
        matches = [row for row in mappings if row["propertyId"] in ("_dev_status", "dev_status")]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one status mapping: {mapping_file}")
        mapping = matches[0]
        if (mapping["dataType"], mapping["byteOrder"], int(mapping["quantity"])) != ("FLOAT32", "ABCD", 2):
            raise ValueError(f"Unsupported status encoding: {mapping}")
        scale, offset = float(mapping["scale"]), float(mapping["offset"])
        payload = struct.pack(">f", (status - offset) / scale)
        with open(device_file, encoding="utf-8-sig", newline="") as source:
            for row in csv.DictReader(source):
                slave = int(row["slaveId"])
                if not 1 <= slave <= 247 or slave in devices:
                    raise ValueError(f"Invalid or duplicate slave address: {slave}")
                devices[slave] = {
                    "name": row["deviceName"],
                    "property": mapping["propertyId"],
                    "function": int(mapping["functionCode"]),
                    "address": int(mapping["address"]),
                    "payload": payload,
                }
    if not devices:
        raise ValueError("No configured devices")
    return devices


def extract_frames(buffer):
    frames = []
    while len(buffer) >= 8:
        function = buffer[1]
        size = 9 + buffer[6] if function in (15, 16) else 8
        if len(buffer) < size:
            break
        frame = bytes(buffer[:size])
        if crc16(frame[:-2]) != frame[-2:]:
            del buffer[0]
            continue
        del buffer[:size]
        frames.append(frame)
    return frames


def response_for(frame, devices):
    slave, function = frame[:2]
    device = devices.get(slave)
    if device is None:
        return None, {"slave": slave, "ignored": "not in configured device lists"}
    address, quantity = struct.unpack(">HH", frame[2:6])
    detail = {"slave": slave, "function": function, "address": address, "quantity": quantity}
    if function != device["function"]:
        code = 1
    elif not 1 <= quantity <= 125:
        code = 3
    elif address < device["address"] or address + quantity > device["address"] + 2:
        code = 2
    else:
        start = (address - device["address"]) * 2
        payload = device["payload"][start:start + quantity * 2]
        detail["statusComplete"] = address == device["address"] and quantity == 2
        return adu(bytes([slave, function, len(payload)]) + payload), detail
    detail["exception"] = code
    return adu(bytes([slave, function | 0x80, code])), detail


def run(args, devices):
    stopped = False

    def stop(*_):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    started = time.monotonic()
    state = {
        "pid": os.getpid(), "target": f"{args.host}:{args.port}", "connected": False,
        "configuredSlaves": sorted(devices), "statusValue": args.status,
        "bytesReceived": 0, "requests": 0, "responses": 0, "exceptions": 0, "respondedSlaves": [],
    }
    answered = set()
    state_path = Path(args.state_file)
    state_path.parent.mkdir(parents=True, exist_ok=True)

    def record(event, **data):
        print(json.dumps({"time": time.strftime("%Y-%m-%dT%H:%M:%S"), "event": event, **data},
                         ensure_ascii=True), flush=True)

    def save():
        state["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        state["elapsedSeconds"] = round(time.monotonic() - started, 1)
        state["respondedSlaves"] = sorted(answered)
        temporary = state_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(state, indent=2), encoding="utf-8")
        temporary.replace(state_path)

    record("ready", configuredSlaves=sorted(devices), statusValue=args.status,
           mode="only configured status registers; other registers return exception 02")
    while not stopped and time.monotonic() - started < args.duration_seconds:
        if args.stop_file and Path(args.stop_file).exists():
            break
        try:
            with socket.create_connection((args.host, args.port), timeout=5) as connection:
                connection.settimeout(1)
                connection.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                state["connected"] = True
                record("connected", local=connection.getsockname(), target=state["target"])
                buffer = bytearray()
                while not stopped and time.monotonic() - started < args.duration_seconds:
                    save()
                    if args.stop_file and Path(args.stop_file).exists():
                        stopped = True
                        break
                    try:
                        chunk = connection.recv(4096)
                    except socket.timeout:
                        continue
                    if not chunk:
                        record("peer_closed")
                        break
                    state["bytesReceived"] += len(chunk)
                    record("received", hex=chunk.hex(" "))
                    buffer.extend(chunk)
                    for frame in extract_frames(buffer):
                        state["requests"] += 1
                        response, detail = response_for(frame, devices)
                        record("request", hex=frame.hex(" "), **detail)
                        if response is not None:
                            connection.sendall(response)
                            state["responses"] += 1
                            if detail.get("exception"):
                                state["exceptions"] += 1
                            if detail.get("statusComplete"):
                                answered.add(detail["slave"])
                            record("response", hex=response.hex(" "), **detail)
        except OSError as error:
            record("connection_error", error=str(error))
        finally:
            state["connected"] = False
            save()
        if not stopped:
            time.sleep(3)
    record("stopped", requests=state["requests"], respondedSlaves=sorted(answered))
    save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--profile", nargs=2, action="append", required=True,
                        metavar=("DEVICES_CSV", "REGISTER_MAP_CSV"))
    parser.add_argument("--status", type=float, default=1)
    parser.add_argument("--duration-seconds", type=float, default=1800)
    parser.add_argument("--state-file", required=True)
    parser.add_argument("--stop-file")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    devices = load_devices(args.profile, args.status)
    if args.check:
        for slave, device in devices.items():
            request = adu(struct.pack(">BBHH", slave, device["function"], device["address"], 2))
            response, detail = response_for(request, devices)
            assert detail["statusComplete"]
            assert crc16(response[:-2]) == response[-2:]
            assert struct.unpack(">f", response[3:7])[0] == args.status
        print(json.dumps({"validatedDevices": len(devices), "slaves": sorted(devices),
                          "status": args.status, "encoding": "FLOAT32 ABCD"}))
        return
    run(args, devices)


if __name__ == "__main__":
    main()
