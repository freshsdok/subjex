package com.subjex.init;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * InitOptions — 解析后的 CLI 参数：目标目录、非交互、跳过、帮助。
 */
public final class InitOptions {

    public static final Path DEFAULT_DIR = Path.of("subjex-dev");

    private final Path targetDir;
    private final boolean yes;
    private final boolean skip;
    private final boolean help;

    public InitOptions(Path targetDir, boolean yes, boolean skip, boolean help) {
        this.targetDir = Objects.requireNonNull(targetDir, "targetDir");
        this.yes = yes;
        this.skip = skip;
        this.help = help;
    }

    public Path targetDir() {
        return targetDir;
    }

    public boolean yes() {
        return yes;
    }

    public boolean skip() {
        return skip;
    }

    public boolean help() {
        return help;
    }

    /**
     * Parse argv. Unknown flags throw {@link IllegalArgumentException}.
     * 解析参数；未知开关抛异常。
     */
    public static InitOptions parse(String[] args) {
        Path dir = DEFAULT_DIR;
        boolean yes = false;
        boolean skip = false;
        boolean help = false;
        if (args == null) {
            return new InitOptions(dir, yes, skip, help);
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--yes", "-y" -> yes = true;
                case "--skip" -> skip = true;
                case "--help", "-h" -> help = true;
                case "--dir" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--dir requires a path");
                    }
                    dir = Path.of(args[++i]);
                }
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option: " + a);
                    }
                    throw new IllegalArgumentException("unexpected argument: " + a);
                }
            }
        }
        return new InitOptions(dir, yes, skip, help);
    }

    public static String helpText() {
        return """
                subjex-init — write local stubs for platform-app / web (skippable)

                Usage:
                  java -jar subjex-init.jar [--dir <path>] [--yes|-y] [--skip] [--help|-h]

                Options:
                  --dir <path>   Target directory (default: ./subjex-dev)
                  --yes, -y      Non-interactive: accept defaults and write
                  --skip         Exit 0 without writing anything
                  --help, -h     Show this help

                Interactive: press Enter for defaults; type skip at the first prompt to abort.
                Does not start Postgres/Redis or run operator bootstrap.

                用法同上。交互时回车用默认；首个提示输入 skip 放弃。不启库/Redis，不开通操作员。
                """;
    }

    /** True when the user typed an explicit skip token. */
    public static boolean isSkipToken(String raw) {
        if (raw == null) {
            return false;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.equals("skip") || t.equals("s");
    }
}
