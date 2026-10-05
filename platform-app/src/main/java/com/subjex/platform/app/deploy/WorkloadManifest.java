package com.subjex.platform.app.deploy;

/**
 * WorkloadManifest — 清单里的一个工作负载，只留人能读的几项。
 * <p>
 * Name, image, the two probe paths, and the memory limit. This is not a live pod.
 * 名字、镜像、两条探针路径，以及内存上限。这不是一个正在运行的 Pod。
 */
public record WorkloadManifest(
        String name,
        String image,
        String livenessPath,
        String readinessPath,
        String memoryLimit) {

    /**
     * Spell a {@code Mi} limit as mebibytes so {@code m} is not read as mega.
     * 把 {@code Mi} 上限写成兆比字节，避免把 {@code m} 读成兆。
     */
    public String memoryLimitInChinese() {
        return spell(memoryLimit, "兆比字节", "按字面读这个数量，m 不是兆");
    }

    /** English twin of {@link #memoryLimitInChinese()}. */
    public String memoryLimitInEnglish() {
        return spell(memoryLimit, "mebibytes", "read this quantity as written; m is not mega");
    }

    private static String spell(String raw, String miWord, String fallback) {
        if (raw.endsWith("Mi") && raw.length() > 2 && digits(raw.substring(0, raw.length() - 2))) {
            return raw.substring(0, raw.length() - 2) + " " + miWord + " (" + raw + ")";
        }
        return raw + " (" + fallback + ")";
    }

    private static boolean digits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
