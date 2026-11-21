package com.subjex.platform.app.admin;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditSink;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/**
 * NdjsonAuditSink — config-enabled file or stdout NDJSON writer; no-op when disabled (WP9-1).
 * 可配置的文件或标准输出 NDJSON 写出器；未启用时为空操作。无厂商 SDK。
 */
public final class NdjsonAuditSink implements AuditSink {

    public static final String TYPE_STDOUT = "stdout";
    public static final String TYPE_FILE = "file";

    private final boolean enabled;
    private final String type;
    private final Path file;
    private final Writer writer;

    /**
     * Production constructor: stdout or append-only file.
     * 生产构造：标准输出或追加文件。
     */
    public NdjsonAuditSink(boolean enabled, String type, Path file) {
        this(enabled, type, file, null);
    }

    /**
     * Test constructor with an injected writer (stdout captured in-memory).
     * 测试构造：注入写出器（标准输出可在内存中捕获）。
     */
    public NdjsonAuditSink(boolean enabled, String type, Path file, Writer writer) {
        this.enabled = enabled;
        this.type = type == null || type.isBlank() ? TYPE_STDOUT : type.trim().toLowerCase(Locale.ROOT);
        this.file = file;
        this.writer = writer;
        if (enabled && TYPE_FILE.equals(this.type) && this.file == null && this.writer == null) {
            throw new IllegalArgumentException("platform.audit.sink.file is required when type=file");
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void accept(AuditEntry entry) {
        if (!enabled || entry == null) {
            return;
        }
        String line = AuditNdjson.line(AuditNdjson.fields(entry));
        try {
            if (writer != null) {
                synchronized (writer) {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                }
                return;
            }
            if (TYPE_FILE.equals(type)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        file,
                        line + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                return;
            }
            // stdout — 标准输出
            OutputStream out = Objects.requireNonNullElse(System.out, OutputStream.nullOutputStream());
            OutputStreamWriter stdout = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            stdout.write(line);
            stdout.write('\n');
            stdout.flush();
        } catch (IOException ex) {
            // Never fail the primary AuditPort write because the sink could not append.
            // 投递失败不得阻断主审计写入。
        }
    }
}
