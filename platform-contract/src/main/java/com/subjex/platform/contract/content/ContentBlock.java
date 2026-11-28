package com.subjex.platform.contract.content;

/**
 * One structured block on a content page.
 * 内容页上的一个结构化块。
 *
 * @param blockId stable id after save; may be null on create input / 保存后的稳定标识；创建输入可为 null
 * @param type block type / 块类型
 * @param text title, body, or link label / 标题、正文或链接文案
 * @param format text format; only meaningful for {@link ContentBlockType#TEXT} / 文本格式，仅 TEXT 有意义
 * @param objectId image object id on the existing object track / 已有对象轨道上的图片对象标识
 * @param href link target / 链接目标
 * @param alt image alternative text / 图片替代文本
 * @param mediaUrl public media path filled only on published public reads / 仅公开已发布读取时填写的媒体路径
 */
public record ContentBlock(
        String blockId,
        ContentBlockType type,
        String text,
        ContentTextFormat format,
        String objectId,
        String href,
        String alt,
        String mediaUrl) {

    public ContentBlock(
            String blockId,
            ContentBlockType type,
            String text,
            ContentTextFormat format,
            String objectId,
            String href,
            String alt) {
        this(blockId, type, text, format, objectId, href, alt, null);
    }
}
