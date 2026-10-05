package com.subjex.init;

import java.io.IOException;
import java.nio.file.Path;

/**
 * SubjexInitMain — 可跳过的本地初始化入口：交互或 {@code --yes} 写入 stub；{@code --skip} 不写。
 * <p>
 * Does not start Postgres, Redis, or operator bootstrap.
 * 不启动 Postgres、Redis，也不执行操作员开通。
 */
public final class SubjexInitMain {

    private SubjexInitMain() {}

    public static void main(String[] args) throws IOException {
        int code = run(args, LineSource.systemIn(), System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Testable entry: returns process exit code without calling {@code System.exit}.
     * 可测入口：返回退出码，不调用 {@code System.exit}。
     */
    public static int run(
            String[] args, LineSource lines, java.io.PrintStream out, java.io.PrintStream err)
            throws IOException {
        InitOptions options;
        try {
            options = InitOptions.parse(args);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            err.println(InitOptions.helpText());
            return 2;
        }
        if (options.help()) {
            out.println(InitOptions.helpText());
            return 0;
        }
        if (options.skip()) {
            out.println("skipped (no files written) / 已跳过（未写文件）");
            return 0;
        }

        InitConfig config;
        if (options.yes()) {
            config = InitConfig.defaults(options.targetDir());
            out.println("Using defaults (--yes) → " + config.targetDir().toAbsolutePath());
        } else {
            config = new InitPrompter(lines, out).prompt(options.targetDir());
            if (config == null) {
                out.println("skipped (no files written) / 已跳过（未写文件）");
                return 0;
            }
        }

        InitGenerator.Result written = new InitGenerator().write(config);
        out.println("wrote " + path(written.envExample()));
        out.println("wrote " + path(written.applicationLocal()));
        out.println("wrote " + path(written.notes()));
        out.println("Done. Review INIT-NOTES.md; init does not bootstrap operators.");
        out.println("完成。请查看 INIT-NOTES.md；init 不会开通操作员。");
        return 0;
    }

    private static String path(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }
}
