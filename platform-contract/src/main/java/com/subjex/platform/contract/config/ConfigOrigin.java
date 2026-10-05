package com.subjex.platform.contract.config;

/**
 * ConfigOrigin — 配置来源称呼：用白话说出这个值从哪一层来。
 * <p>
 * The API word is short. The Chinese and English labels are for people.
 * 接口里用短词。中文和英文标签给人看。
 */
public enum ConfigOrigin {

    LOCAL("local", "本地文件", "local file"),
    OVERRIDE("override", "内存覆盖", "memory override");

    private final String apiWord;
    private final String chinese;
    private final String english;

    ConfigOrigin(String apiWord, String chinese, String english) {
        this.apiWord = apiWord;
        this.chinese = chinese;
        this.english = english;
    }

    /** Short word on the wire — 线上的短词。 */
    public String apiWord() {
        return apiWord;
    }

    /** Chinese label for people — 给人看的中文。 */
    public String chinese() {
        return chinese;
    }

    /** English label for people — 给人看的英文。 */
    public String english() {
        return english;
    }

    public static ConfigOrigin fromApiWord(String word) {
        if (word == null || word.isBlank()) {
            throw new IllegalArgumentException("config origin is missing");
        }
        for (ConfigOrigin origin : values()) {
            if (origin.apiWord.equals(word)) {
                return origin;
            }
        }
        throw new IllegalArgumentException("config origin is unknown");
    }
}
