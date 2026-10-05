package com.subjex.page.declare;

/**
 * ListPageSpec — 列表页：控制台路径、读取列表的 API、可选的数组字段名。
 * <p>
 * {@code itemsKey} names the JSON array field when the API wraps rows (e.g. {@code submissions}).
 * {@code itemsKey} 在 API 把行包在对象里时指出数组字段名（例如 {@code submissions}）。
 */
public record ListPageSpec(String path, String apiPath, String itemsKey) {}
