package com.subjex.init;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * LineSource — 一行输入源，便于单测注入；生产用 stdin。
 */
@FunctionalInterface
public interface LineSource {

    String readLine() throws IOException;

    static LineSource systemIn() {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return () -> {
            String line = reader.readLine();
            return line == null ? "" : line;
        };
    }

    static LineSource of(Supplier<String> supplier) {
        return () -> {
            String v = supplier.get();
            return v == null ? "" : v;
        };
    }
}
