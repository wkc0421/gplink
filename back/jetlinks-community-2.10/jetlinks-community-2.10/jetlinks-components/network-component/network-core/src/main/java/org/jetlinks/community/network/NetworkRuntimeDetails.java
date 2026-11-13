package org.jetlinks.community.network;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Point-in-time values from this instance. Omitted fields are not available. */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NetworkRuntimeDetails {

    public static final int MAX_CONNECTIONS = 100;

    private Boolean connected;
    private Boolean connecting;
    private Boolean listening;
    private String remoteAddress;
    private String localAddress;
    private String bindAddress;
    private String clientId;
    private Integer connectionCount;
    private List<Connection> connections;

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Connection {
        private final String remoteHost;
        private final Integer remotePort;
        private final String clientId;
    }
}
