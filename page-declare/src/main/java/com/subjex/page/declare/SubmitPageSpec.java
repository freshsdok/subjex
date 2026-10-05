package com.subjex.page.declare;

/**
 * SubmitPageSpec — 提交页：填写路径、POST 的 API、提交成功后的跳转。
 * <p>
 * {@code redirectTo} is a console path, not an external URL.
 * {@code redirectTo} 是控制台路径，不是外部 URL。
 */
public record SubmitPageSpec(String path, String apiPath, String redirectTo) {}
