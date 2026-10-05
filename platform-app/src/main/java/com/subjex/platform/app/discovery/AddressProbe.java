package com.subjex.platform.app.discovery;

/**
 * AddressProbe — 地址探测：此刻这个主机和端口答不答应。
 * <p>
 * The only words are {@code up} and {@code unknown}. There is no passing, critical, or weight.
 * 只有 {@code up} 和 {@code unknown} 两个词。没有 passing、critical，也没有权重。
 */
public interface AddressProbe {

    /** A short connect succeeded — 短连接成功。 */
    String UP = "up";

    /** We could not confirm an answer — 没能确认有应答。 */
    String UNKNOWN = "unknown";

    /**
     * @return {@code up} or {@code unknown} / {@code up} 或 {@code unknown}
     */
    String word(String host, int port);
}
