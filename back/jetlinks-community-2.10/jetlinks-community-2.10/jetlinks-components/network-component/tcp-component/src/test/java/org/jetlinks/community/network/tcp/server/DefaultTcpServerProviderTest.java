package org.jetlinks.community.network.tcp.server;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.jetlinks.community.network.Network;
import org.jetlinks.community.network.security.CertificateManager;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.community.network.tcp.parser.PayloadParserBuilder;
import org.jetlinks.community.network.tcp.parser.PayloadParserType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultTcpServerProviderTest {

    @Test
    void startupWaitsForEveryListenerAndClosesTheValidationParser() {
        Fixture fixture = new Fixture();
        AtomicReference<Network> result = new AtomicReference<>();
        fixture.provider.createNetwork(fixture.properties).subscribe(result::set);
        verify(fixture.parser).close();
        fixture.firstListen.complete(fixture.first);
        assertNull(result.get());

        fixture.secondListen.complete(fixture.second);
        assertTrue(result.get().isAlive());
        result.get().shutdownAsync().block();
    }

    @Test
    void failedBindWaitsForCleanupAndClosesALateSuccessfulBind() {
        Fixture fixture = new Fixture();
        Promise<Void> firstClosed = Promise.promise();
        Promise<Void> secondClosed = Promise.promise();
        when(fixture.first.close()).thenReturn(firstClosed.future());
        when(fixture.second.close()).thenReturn(secondClosed.future());
        IllegalStateException failure = new IllegalStateException("occupied port");
        AtomicReference<Throwable> error = new AtomicReference<>();
        fixture.provider.createNetwork(fixture.properties).subscribe(ignore -> fail("must not start"), error::set);

        fixture.firstListen.fail(failure);
        verify(fixture.first).close();
        verify(fixture.second).close();
        assertNull(error.get(), "failure must wait for the close futures");
        firstClosed.complete();
        assertNull(error.get());
        secondClosed.complete();
        assertSame(failure, error.get());

        fixture.secondListen.complete(fixture.second);
        verify(fixture.second, times(2)).close();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cancellingStartupClosesListenersAndRejectsOldAcceptCallbacks() {
        Fixture fixture = new Fixture();
        Disposable starting = fixture.provider.createNetwork(fixture.properties).subscribe();
        ArgumentCaptor<Handler<NetSocket>> accepted = ArgumentCaptor.forClass((Class) Handler.class);
        verify(fixture.first).connectHandler(accepted.capture());

        starting.dispose();
        verify(fixture.first).close();
        verify(fixture.second).close();
        fixture.firstListen.complete(fixture.first);
        fixture.secondListen.complete(fixture.second);
        verify(fixture.first, times(2)).close();
        verify(fixture.second, times(2)).close();

        NetSocket lateSocket = mock(NetSocket.class);
        accepted.getValue().handle(lateSocket);
        verify(lateSocket).close();
    }

    @Test
    void partialAllocationFailureClosesAlreadyCreatedListeners() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("allocation failed");
        when(fixture.vertx.createNetServer(any(NetServerOptions.class)))
                .thenReturn(fixture.first).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.provider.createNetwork(fixture.properties).block()));
        verify(fixture.first).close();
        verify(fixture.first, never()).listen(any(SocketAddress.class));
        verify(fixture.parser).close();
    }

    @Test
    void parserValidationFailsBeforeAllocatingListeners() {
        Fixture fixture = new Fixture();
        IllegalArgumentException failure = new IllegalArgumentException("invalid parser");
        when(fixture.parsers.build(fixture.properties.getParserType(), fixture.properties)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> fixture.provider.createNetwork(fixture.properties).block()));
        verifyNoInteractions(fixture.vertx);
    }

    @Test
    void failedHandlerInstallationClosesEveryAllocatedListener() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("cannot install handler");
        when(fixture.second.connectHandler(any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.provider.createNetwork(fixture.properties).block()));
        verify(fixture.first).close();
        verify(fixture.second).close();
        verify(fixture.first, never()).listen(any(SocketAddress.class));
    }

    private static final class Fixture {
        final Vertx vertx = mock(Vertx.class);
        final NetServer first = mock(NetServer.class);
        final NetServer second = mock(NetServer.class);
        final Promise<NetServer> firstListen = Promise.promise();
        final Promise<NetServer> secondListen = Promise.promise();
        final PayloadParserBuilder parsers = mock(PayloadParserBuilder.class);
        final PayloadParser parser = mock(PayloadParser.class);
        final TcpServerProperties properties = new TcpServerProperties();
        final DefaultTcpServerProvider provider = new DefaultTcpServerProvider(
                mock(CertificateManager.class), vertx, parsers);

        Fixture() {
            properties.setId("server");
            properties.setHost("127.0.0.1");
            properties.setPort(10001);
            properties.setInstance(2);
            properties.setParserType(PayloadParserType.DIRECT);
            when(parsers.build(properties.getParserType(), properties)).thenReturn(() -> parser);
            when(vertx.createNetServer(any(NetServerOptions.class))).thenReturn(first, second);
            when(first.listen(any(SocketAddress.class))).thenReturn(firstListen.future());
            when(second.listen(any(SocketAddress.class))).thenReturn(secondListen.future());
            when(first.close()).thenReturn(Future.succeededFuture());
            when(second.close()).thenReturn(Future.succeededFuture());
        }
    }
}
