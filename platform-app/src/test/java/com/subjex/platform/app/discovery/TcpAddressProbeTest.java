package com.subjex.platform.app.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TcpAddressProbeTest {

    @Test
    void openPortIsUpAndClosedPortIsUnknown() throws Exception {
        TcpAddressProbe probe = new TcpAddressProbe(Duration.ofMillis(300));
        try (ServerSocket open = new ServerSocket(0)) {
            assertEquals(AddressProbe.UP, probe.word("127.0.0.1", open.getLocalPort()));
        }
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertEquals(AddressProbe.UNKNOWN, probe.word("127.0.0.1", closed));
    }
}
