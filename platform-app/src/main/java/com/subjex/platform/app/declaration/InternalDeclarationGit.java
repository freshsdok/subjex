package com.subjex.platform.app.declaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * InternalDeclarationGit — 平台内声明晋升用的本地 git 工作树（系统 git CLI，非 GitHub / 非 JGit）。
 * <p>
 * Layout: {@code {dir}/{tenantId}/{kind}/{key}.yaml}. Local commit only — never pushes.
 * Path segments reject {@code ..} and separators (fail-closed).
 * 路径：{@code {dir}/{tenantId}/{kind}/{key}.yaml}。仅本地提交——不 push。
 * 路径段拒绝 {@code ..} 与分隔符（失败关闭）。
 */
public final class InternalDeclarationGit {

    private static final String GIT_USER_NAME = "subjex-promote";
    private static final String GIT_USER_EMAIL = "subjex-promote@local";
    private static final long GIT_TIMEOUT_SECONDS = 30L;

    /**
     * Ensure {@code dir} is a non-bare git working tree (init + local identity if needed) —
     * 确保 {@code dir} 为非裸工作树（必要时 init 并设本地身份）。
     */
    public void ensureRepo(Path dir) {
        Path root = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("could not create declaration git dir " + root, ex);
        }
        if (!Files.isDirectory(root.resolve(".git"))) {
            runGit(root, "init");
            runGit(root, "config", "user.email", GIT_USER_EMAIL);
            runGit(root, "config", "user.name", GIT_USER_NAME);
            runGit(root, "config", "commit.gpgsign", "false");
        }
    }

    /**
     * Write YAML, commit, return HEAD sha — 写入 YAML、提交，返回 HEAD sha。
     */
    public String promote(
            Path dir,
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            int revision,
            String yamlBody,
            String subjectId) {
        Path root = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(yamlBody, "yamlBody");
        Objects.requireNonNull(subjectId, "subjectId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        String tid = sanitizePathSegment(tenantId, "tenantId");
        String key = sanitizePathSegment(declarationKey, "declarationKey");
        ensureRepo(root);
        Path relative = Path.of(tid, kind.wireName(), key + ".yaml");
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("path escapes declaration git dir");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, yamlBody, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("could not write " + file, ex);
        }
        String relPosix = tid + "/" + kind.wireName() + "/" + key + ".yaml";
        runGit(root, "add", "--", relPosix);
        String message =
                "promote " + kind.wireName() + "/" + key + "@r" + revision + " tenant=" + tid;
        // Allow empty? No — fail if nothing to commit would leave prior HEAD; force message with -m.
        // If content unchanged, git commit fails (non-zero) — fail-closed as required.
        runGit(root, "commit", "-m", message);
        return runGit(root, "rev-parse", "HEAD").trim();
    }

    /** Reject blank, {@code ..}, and path separators — 拒绝空白、{@code ..} 与路径分隔符。 */
    static String sanitizePathSegment(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        String trimmed = value.trim();
        if ("..".equals(trimmed)
                || trimmed.contains("..")
                || trimmed.contains("/")
                || trimmed.contains("\\")
                || trimmed.indexOf(0) >= 0) {
            throw new IllegalArgumentException(label + " rejects path traversal or separators");
        }
        return trimmed;
    }

    private static String runGit(Path workTree, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String arg : args) {
            command.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workTree.toFile());
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("git timed out: " + String.join(" ", command));
            }
            int code = process.exitValue();
            if (code != 0) {
                throw new IllegalStateException(
                        "git failed (" + code + "): " + String.join(" ", command)
                                + (stderr.isBlank() ? "" : " — " + stderr.trim())
                                + (stdout.isBlank() ? "" : " — " + stdout.trim()));
            }
            return stdout;
        } catch (IOException ex) {
            throw new IllegalStateException("git could not start: " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git interrupted: " + String.join(" ", command), ex);
        }
    }
}
