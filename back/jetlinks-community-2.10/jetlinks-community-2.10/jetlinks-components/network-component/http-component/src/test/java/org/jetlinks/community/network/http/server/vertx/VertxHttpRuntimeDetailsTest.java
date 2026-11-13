package org.jetlinks.community.network.http.server.vertx;

import io.vertx.core.http.HttpServer;
import org.jetlinks.community.network.NetworkRuntimeDetails;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VertxHttpRuntimeDetailsTest {

    @Test
    void reportsOnlySuccessfulBindsFromTheCurrentServer() {
        VertxHttpServer server = new VertxHttpServer();
        HttpServer first = mock(HttpServer.class, RETURNS_SELF);
        when(first.actualPort()).thenReturn(12345);
        server.setHttpServers(new ArrayList<>(List.of(first)));
        assertFalse(server.getRuntimeDetails().getListening());

        server.markListening(first, "127.0.0.1");
        NetworkRuntimeDetails listening = server.getRuntimeDetails();
        assertTrue(listening.getListening());
        assertEquals("127.0.0.1:12345", listening.getBindAddress());

        HttpServer second = mock(HttpServer.class, RETURNS_SELF);
        server.setHttpServers(new ArrayList<>(List.of(second)));
        server.markListening(first, "127.0.0.1");
        assertFalse(server.getRuntimeDetails().getListening());
        server.shutdown();
        server.markListening(second, "127.0.0.1");
        assertNull(server.getRuntimeDetails().getBindAddress());
    }
}
