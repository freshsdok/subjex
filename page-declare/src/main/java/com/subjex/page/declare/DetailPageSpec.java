package com.subjex.page.declare;

/**
 * DetailPageSpec — 详情页：路径模板、读取数据的 API、行标识字段。
 * <p>
 * When there is no GET-by-id upstream, the console loads {@code apiPath} and picks the row by {@code idField}.
 * 上游没有按 id 查询时，控制台读 {@code apiPath} 再用 {@code idField} 挑出一行。
 */
public record DetailPageSpec(String path, String apiPath, String itemsKey, String idField) {}
