import signal
import socket
import struct
import threading
import time


HOST = "0.0.0.0"
PORT = 40020


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc


def make_register_bytes(address: int, quantity: int) -> bytes:
    # Temperature: 23.4 C as INT16 with scale 0.1, address 0.
    if address == 0 and quantity == 1:
        return struct.pack(">H", 234)

    # Voltage: 220.0 V as FLOAT32 with CDAB byte order, address 2.
    if address == 2 and quantity == 2:
        ieee_abcd = struct.pack(">f", 220.0)
        return ieee_abcd[2:4] + ieee_abcd[0:2]

    return b"\x00\x00" * quantity


def make_modbus_pdu(unit: int, function: int, address: int, quantity: int) -> bytes:
    if function != 3 or quantity < 1 or quantity > 125:
        return bytes([unit, function | 0x80, 0x02])
    data = make_register_bytes(address, quantity)
    return bytes([unit, function, len(data)]) + data


def parse_request(packet: bytes):
    # Modbus TCP MBAP: transaction, protocol, length, unit, function, address, quantity.
    if len(packet) >= 12:
        protocol = int.from_bytes(packet[2:4], "big")
        declared_length = int.from_bytes(packet[4:6], "big")
        if protocol == 0 and declared_length == len(packet) - 6:
            unit = packet[6]
            function = packet[7]
            address = int.from_bytes(packet[8:10], "big")
            quantity = int.from_bytes(packet[10:12], "big")
            return "tcp", (packet[0:2], packet[2:4], unit, function, address, quantity)

    # Modbus RTU over TCP: unit, function, address, quantity, CRC.
    if len(packet) >= 8:
        unit = packet[0]
        function = packet[1]
        address = int.from_bytes(packet[2:4], "big")
        quantity = int.from_bytes(packet[4:6], "big")
        received_crc = int.from_bytes(packet[6:8], "little")
        expected_crc = crc16_modbus(packet[:6])
        if received_crc != expected_crc:
            print(f"ignoring bad RTU CRC received={received_crc:04x} expected={expected_crc:04x}", flush=True)
            return None
        return "rtu", (unit, function, address, quantity)
    return None


def build_response(kind, parsed):
    if kind == "tcp":
        transaction, protocol, unit, function, address, quantity = parsed
        pdu = make_modbus_pdu(unit, function, address, quantity)
        return transaction + protocol + struct.pack(">H", len(pdu)) + pdu

    unit, function, address, quantity = parsed
    body = make_modbus_pdu(unit, function, address, quantity)
    crc = crc16_modbus(body)
    return body + struct.pack("<H", crc)


def handle_client(conn, peer):
    print(f"client connected: {peer}", flush=True)
    buffer = b""
    try:
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                return
            buffer += chunk
            while True:
                packet_len = None
                if len(buffer) >= 6 and int.from_bytes(buffer[2:4], "big") == 0:
                    declared_length = int.from_bytes(buffer[4:6], "big")
                    if 2 <= declared_length <= 253:
                        candidate_len = 6 + declared_length
                        if len(buffer) < candidate_len:
                            break
                        packet_len = candidate_len
                if packet_len is None and len(buffer) >= 8:
                    packet_len = 8
                if packet_len is None:
                    break

                packet = buffer[:packet_len]
                buffer = buffer[packet_len:]
                parsed = parse_request(packet)
                if parsed is None:
                    continue
                kind, payload = parsed
                response = build_response(kind, payload)
                print(f"{kind} request={packet.hex(' ')} response={response.hex(' ')}", flush=True)
                conn.sendall(response)
    except (ConnectionError, OSError, socket.timeout) as exc:
        print(f"client {peer} ended: {exc}", flush=True)
    finally:
        conn.close()


stop_event = threading.Event()


def stop_server(_signum, _frame):
    stop_event.set()


signal.signal(signal.SIGINT, stop_server)
signal.signal(signal.SIGTERM, stop_server)

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(20)
    server.settimeout(1)
    print(f"Modbus test server listening on {HOST}:{PORT}", flush=True)
    while not stop_event.is_set():
        try:
            conn, peer = server.accept()
        except socket.timeout:
            continue
        threading.Thread(target=handle_client, args=(conn, peer), daemon=True).start()

print("Modbus test server stopped", flush=True)
