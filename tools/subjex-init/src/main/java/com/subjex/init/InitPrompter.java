package com.subjex.init;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;

/**
 * InitPrompter — 交互问答；首问可 skip；空输入用默认值。
 */
public final class InitPrompter {

    private final LineSource lines;
    private final PrintStream out;

    public InitPrompter(LineSource lines, PrintStream out) {
        this.lines = lines;
        this.out = out;
    }

    /**
     * Prompt for config, or {@code null} if the user chose skip.
     * 询问配置；用户选择 skip 时返回 {@code null}。
     */
    public InitConfig prompt(Path defaultDir) throws IOException {
        out.println("subjex-init (skippable). Enter accepts defaults; type skip to abort.");
        out.println("可跳过。回车用默认；输入 skip 放弃。");
        String dirRaw = ask("Target directory", defaultDir.toString());
        if (InitOptions.isSkipToken(dirRaw)) {
            return null;
        }
        Path dir = Path.of(blankTo(dirRaw, defaultDir.toString()));

        String vendorRaw = ask("JDBC vendor (postgresql|mysql)", InitConfig.DEFAULT_VENDOR);
        if (InitOptions.isSkipToken(vendorRaw)) {
            return null;
        }
        String vendor = InitConfig.normalizeVendor(blankTo(vendorRaw, InitConfig.DEFAULT_VENDOR));

        String urlDefault = InitConfig.defaultJdbcUrl(vendor);
        String urlRaw = ask("JDBC URL", urlDefault);
        if (InitOptions.isSkipToken(urlRaw)) {
            return null;
        }
        String url = blankTo(urlRaw, urlDefault);

        String userRaw = ask("JDBC username", InitConfig.DEFAULT_USER);
        if (InitOptions.isSkipToken(userRaw)) {
            return null;
        }
        String user = blankTo(userRaw, InitConfig.DEFAULT_USER);

        String passRaw = ask("JDBC password", InitConfig.DEFAULT_PASSWORD);
        if (InitOptions.isSkipToken(passRaw)) {
            return null;
        }
        String pass = blankTo(passRaw, InitConfig.DEFAULT_PASSWORD);

        String redisRaw = ask("Include Redis / OPERATOR_SESSION_SECRET notes? (Y/n)", "Y");
        if (InitOptions.isSkipToken(redisRaw)) {
            return null;
        }
        boolean redis = parseYes(blankTo(redisRaw, "Y"), true);

        return new InitConfig(dir, vendor, url, user, pass, redis);
    }

    private String ask(String label, String defaultValue) throws IOException {
        out.print(label + " [" + defaultValue + "]: ");
        out.flush();
        return lines.readLine();
    }

    private static String blankTo(String raw, String defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim();
    }

    static boolean parseYes(String raw, boolean defaultYes) {
        if (raw == null || raw.isBlank()) {
            return defaultYes;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.equals("y") || t.equals("yes")) {
            return true;
        }
        if (t.equals("n") || t.equals("no")) {
            return false;
        }
        return defaultYes;
    }
}
