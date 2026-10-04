package com.subjex.platform.contract.extension;

/**
 * PlatformExtension — 平台扩展：编译进应用的一块能力的名字。
 * <p>
 * Implement the interface in the application module. Do not load an implementation from a remote address.
 * 在应用模块里实现这个接口。不要从远程地址装载实现。
 */
public interface PlatformExtension {

    /**
     * @return stable name of this compiled-in extension / 这个编译期扩展的稳定名字
     */
    String extensionName();
}
