package org.jetlinks.community.device.modbus;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ModbusAccessRequest {

    private String id;

    private String name;

    private Connection connection = new Connection();

    private Host host = new Host();

    private List<SlaveType> slaveTypes = new ArrayList<>();

    private List<Slave> slaves = new ArrayList<>();

    private ModbusCollectionPolicy defaultPolicy = ModbusCollectionPolicy.defaults();

    @Getter
    @Setter
    public static class Connection {
        private ConnectionMode mode = ConnectionMode.PLATFORM_CONNECTS_GATEWAY;
        private String host;
        private Integer port;
        private String listenHost = "0.0.0.0";
        private Integer listenPort;
    }

    public enum ConnectionMode {
        PLATFORM_CONNECTS_GATEWAY,
        GATEWAY_CONNECTS_PLATFORM
    }

    @Getter
    @Setter
    public static class Host {
        private String deviceId;
        private String name;
        private String code;
        private String description;
    }

    @Getter
    @Setter
    public static class SlaveType {
        private String id;
        private String name;
        private List<Map<String, Object>> registerMap = new ArrayList<>();
        private ModbusCollectionPolicy collectionPolicy;
    }

    @Getter
    @Setter
    public static class Slave {
        private String deviceId;
        private String name;
        private String code;
        private String description;
        private String typeId;
        private Integer slaveId;
        private ModbusCollectionPolicy collectionPolicy;
    }
}
