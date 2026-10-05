package com.subjex.platform.app.discovery;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * TcpAddressProbe — TCP 地址探测：连得上才说 up，否则说 unknown。
 * <p>
 * A registration is not proof that the process answers. This probe does not score health and does not draw a chart.
 * 登记本身不能证明进程还在应答。这个探测不打健康分，也不画图。
 */
public final class TcpAddressProbe implements AddressProbe {

    private final int timeoutMillis;

    public TcpAddressProbe(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("probe timeout is out of range");
        }
        this.timeoutMillis = (int) timeout.toMillis();
    }

    @Override
    public String word(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return UNKNOWN;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return UP;
        } catch (IOException ex) {
            return UNKNOWN;
        }
    }
}
