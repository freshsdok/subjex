package com.subjex.platform.app.content;

import com.subjex.platform.contract.content.ContentBlock;
import java.util.List;

/**
 * JSON body for creating or updating a content page.
 * 创建或更新内容页的 JSON 体。
 */
public record SaveContentPageRequest(String slug, String title, List<ContentBlock> blocks) {}
