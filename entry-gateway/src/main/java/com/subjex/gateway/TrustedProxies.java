package com.subjex.gateway;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TrustedProxies — 可信代理集合：IP 或 CIDR 字面量，决定网关是否采信 {@code X-Forwarded-For}。
 * <p>
 * Only literals are accepted; host names are refused so matching never triggers a DNS lookup.
 * An empty set means no proxy is trusted and forwarded headers are ignored.
 * 只接受字面量；拒绝主机名，匹配时绝不触发 DNS 查询。空集合表示不信任任何代理，转发头一律忽略。
 */
public final class TrustedProxies {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private static final TrustedProxies NONE = new TrustedProxies(List.of());

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    public static TrustedProxies none() {
        return NONE;
    }

    /**
     * Parses entries such as {@code 10.0.0.0/8}, {@code 192.0.2.10} or {@code ::1}. Blank entries are skipped.
     *
     * @throws IllegalArgumentException when an entry is not an IP or CIDR literal
     */
    public static TrustedProxies of(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return NONE;
        }
        List<Range> parsed = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            parsed.add(Range.parse(raw.trim()));
        }
        return parsed.isEmpty() ? NONE : new TrustedProxies(parsed);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** True when {@code address} is an IP literal inside one of the configured ranges. */
    public boolean contains(String address) {
        if (ranges.isEmpty()) {
            return false;
        }
        byte[] bytes = literal(address);
        if (bytes == null) {
            return false;
        }
        for (Range range : ranges) {
            if (range.matches(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** Address bytes for an IP literal, or {@code null}. Never resolves host names. */
    static byte[] literal(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (IPV4.matcher(candidate).matches()) {
            return ipv4(candidate);
        }
        if (!IPV6.matcher(candidate).matches()) {
            return null;
        }
        // Contains ':' and only hex digits, ':' and '.', so the JDK parses it as a literal and never does DNS.
        try {
            return InetAddress.getByName(candidate).getAddress();
        } catch (UnknownHostException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static byte[] ipv4(String dotted) {
        String[] parts = dotted.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int octet = Integer.parseInt(parts[i]);
            if (octet > 255) {
                return null;
            }
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    private record Range(byte[] network, int prefixBits) {

        static Range parse(String entry) {
            int slash = entry.indexOf('/');
            String host = slash < 0 ? entry : entry.substring(0, slash);
            byte[] network = literal(host);
            if (network == null) {
                throw new IllegalArgumentException(
                        "gateway.trusted-proxies entry is not an IP or CIDR literal: " + entry);
            }
            int maxBits = network.length * 8;
            int bits = maxBits;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(
                            "gateway.trusted-proxies entry has a bad prefix length: " + entry);
                }
                if (bits < 0 || bits > maxBits) {
                    throw new IllegalArgumentException(
                            "gateway.trusted-proxies entry has a bad prefix length: " + entry);
                }
            }
            return new Range(network, bits);
        }

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remaining = prefixBits % 8;
            if (remaining == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remaining)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
