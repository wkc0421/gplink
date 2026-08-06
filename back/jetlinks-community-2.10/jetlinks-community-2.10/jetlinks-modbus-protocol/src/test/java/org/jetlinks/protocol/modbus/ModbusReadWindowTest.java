package org.jetlinks.protocol.modbus;

import org.jetlinks.protocol.modbus.frame.ModbusFunctionCode;
import org.jetlinks.protocol.modbus.frame.ModbusRequest;
import org.jetlinks.protocol.modbus.mapping.ByteOrder;
import org.jetlinks.protocol.modbus.mapping.RegisterDataType;
import org.jetlinks.protocol.modbus.mapping.RegisterMapping;
import org.jetlinks.protocol.modbus.mapping.RegisterMappingTable;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ModbusReadWindowTest {

    @Test
    public void usesDefaultRegisterWindowAndGapTwo() {
        RegisterMappingTable table = RegisterMappingTable.of(Arrays.asList(
                mapping("a", ModbusFunctionCode.READ_HOLDING_REGISTERS, 0),
                mapping("b", ModbusFunctionCode.READ_HOLDING_REGISTERS, 3),
                mapping("c", ModbusFunctionCode.READ_HOLDING_REGISTERS, 10)));

        List<ModbusRequest> requests = new ModbusRtuCodec().buildReadRequestsForTest(
                1, table, Arrays.asList("c", "a", "b"), 60, 512, 2);

        assertEquals(2, requests.size());
        assertEquals(0, requests.get(0).getAddress());
        assertEquals(4, requests.get(0).getQuantity());
        assertEquals(10, requests.get(1).getAddress());
        assertEquals(1, requests.get(1).getQuantity());
    }

    @Test
    public void splitsAtSixtyRegisterBoundary() {
        RegisterMappingTable table = RegisterMappingTable.of(Arrays.asList(
                mapping("a", ModbusFunctionCode.READ_INPUT_REGISTERS, 0),
                mapping("b", ModbusFunctionCode.READ_INPUT_REGISTERS, 59),
                mapping("c", ModbusFunctionCode.READ_INPUT_REGISTERS, 60)));

        List<ModbusRequest> requests = new ModbusRtuCodec().buildReadRequestsForTest(
                1, table, Arrays.asList("a", "b", "c"), 60, 512, 60);

        assertEquals(2, requests.size());
        assertEquals(60, requests.get(0).getQuantity());
        assertEquals(60, requests.get(1).getAddress());
    }

    @Test
    public void mixedFunctionCodesAlwaysUseDifferentWindows() {
        RegisterMappingTable table = RegisterMappingTable.of(Arrays.asList(
                mapping("holding", ModbusFunctionCode.READ_HOLDING_REGISTERS, 0),
                mapping("input", ModbusFunctionCode.READ_INPUT_REGISTERS, 1),
                bitMapping("coil", ModbusFunctionCode.READ_COILS, 2)));

        List<ModbusRequest> requests = new ModbusRtuCodec().buildReadRequestsForTest(
                1, table, Arrays.asList("holding", "input", "coil"), 60, 512, 2);

        assertEquals(3, requests.size());
        assertEquals(ModbusFunctionCode.READ_COILS, requests.get(0).getFunction());
        assertEquals(ModbusFunctionCode.READ_HOLDING_REGISTERS, requests.get(1).getFunction());
        assertEquals(ModbusFunctionCode.READ_INPUT_REGISTERS, requests.get(2).getFunction());
    }

    @Test
    public void bitWindowUses512AndProtocolHardLimitsAreClamped() {
        List<RegisterMapping> mappings = new ArrayList<>();
        mappings.add(bitMapping("bit0", ModbusFunctionCode.READ_DISCRETE_INPUTS, 0));
        mappings.add(bitMapping("bit511", ModbusFunctionCode.READ_DISCRETE_INPUTS, 511));
        mappings.add(bitMapping("bit512", ModbusFunctionCode.READ_DISCRETE_INPUTS, 512));
        RegisterMappingTable table = RegisterMappingTable.of(mappings);

        List<ModbusRequest> defaults = new ModbusRtuCodec().buildReadRequestsForTest(
                1, table, Arrays.asList("bit0", "bit511", "bit512"), 60, 512, 600);
        assertEquals(2, defaults.size());
        assertEquals(512, defaults.get(0).getQuantity());

        List<ModbusRequest> hardLimit = new ModbusRtuCodec().buildReadRequestsForTest(
                1, table, Arrays.asList("bit0", "bit511", "bit512"), 60, 9999, 600);
        assertEquals(1, hardLimit.size());
        assertEquals(513, hardLimit.get(0).getQuantity());
    }

    private RegisterMapping mapping(String id, ModbusFunctionCode function, int address) {
        return RegisterMapping.builder()
                .propertyId(id)
                .functionCode(function)
                .address(address)
                .quantity(1)
                .dataType(RegisterDataType.UINT16)
                .byteOrder(ByteOrder.ABCD)
                .scale(1)
                .offset(0)
                .writable(false)
                .build();
    }

    private RegisterMapping bitMapping(String id, ModbusFunctionCode function, int address) {
        return RegisterMapping.builder()
                .propertyId(id)
                .functionCode(function)
                .address(address)
                .quantity(1)
                .dataType(RegisterDataType.BIT)
                .byteOrder(ByteOrder.ABCD)
                .scale(1)
                .offset(0)
                .writable(false)
                .build();
    }
}
