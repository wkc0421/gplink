package org.jetlinks.community.device.modbus;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ModbusAccessResponse {

    private String id;

    private String name;

    private String networkId;

    private String gatewayId;

    private String gatewayProductId;

    private String gatewayDeviceId;

    private List<String> slaveProductIds = new ArrayList<>();

    private List<String> slaveDeviceIds = new ArrayList<>();

    private ModbusCollectionPolicy defaultPolicy;
}
