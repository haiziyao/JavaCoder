package com.jcoder.ui.web;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 序列化辅助。复用项目已有的 Jackson。
 *
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
