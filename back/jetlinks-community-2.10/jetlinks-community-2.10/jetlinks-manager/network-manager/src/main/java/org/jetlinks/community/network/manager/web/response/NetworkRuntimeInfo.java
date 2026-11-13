package org.jetlinks.community.network.manager.web.response;

import lombok.Getter;
import org.jetlinks.community.network.Network;
import org.jetlinks.community.network.NetworkRuntimeDetails;
import org.jetlinks.community.network.manager.entity.NetworkConfigEntity;

import javax.annotation.Nullable;

@Getter
public class NetworkRuntimeInfo {
    private final String id;
    private final String type;
    private final String serverId;
    private final String configState;
    private final boolean available;
    private final Boolean alive;
    private final NetworkRuntimeDetails details;

    public NetworkRuntimeInfo(NetworkConfigEntity config, String serverId, @Nullable Network network) {
        this.id = config.getId();
        this.type = config.getType();
        this.serverId = serverId;
        this.configState = config.getState() == null ? null : config.getState().getValue();
        this.available = network != null;
        if (network == null) {
            this.details = new NetworkRuntimeDetails();
            this.alive = null;
        } else {
            this.details = network.getRuntimeDetails();
            // Some providers register server instances before their asynchronous bind has completed.
            this.alive = details.getListening() != null ? details.getListening() : network.isAlive();
        }
    }
}
