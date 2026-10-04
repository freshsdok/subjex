package com.subjex.platform.app.extension;

import com.subjex.platform.contract.extension.PlatformExtension;

/**
 * TaskDeliveryExtension — 任务投递扩展：编译进 platform-app 的那一块投递能力。
 * <p>
 * The class is on the classpath because it was compiled here. Nothing fetches it at runtime.
 * 这个类在 classpath 上，是因为在这里编译出来的。运行时不会再去拉取它。
 */
public final class TaskDeliveryExtension implements PlatformExtension {

    @Override
    public String extensionName() {
        return "task-delivery";
    }
}
