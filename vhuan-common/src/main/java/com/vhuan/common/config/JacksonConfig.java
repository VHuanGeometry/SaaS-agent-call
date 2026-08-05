package com.vhuan.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 序列化配置
 * <p>
 * 统一全局 JSON 序列化行为，避免前端 Long 精度丢失、日期格式不一致、null 字段冗余等问题：
 * <ul>
 *   <li>日期格式：yyyy-MM-dd HH:mm:ss，时区 Asia/Shanghai</li>
 *   <li>不序列化 null 字段（减少响应体体积）</li>
 *   <li>Long 超 JS 安全整数范围（2^53）转 String，防止前端精度丢失</li>
 *   <li>LocalDateTime 按统一格式序列化/反序列化</li>
 * </ul>
 * </p>
 */
@Configuration
public class JacksonConfig {

    /** 统一日期时间格式 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** JS 安全整数最大值（2^53），超过此值前端 Number 会丢失精度 */
    private static final long JS_SAFE_INTEGER_MAX = 9007199254740992L;

    /**
     * 自定义 Jackson 构建器
     * <p>使用 Customizer 增量修改 Spring Boot 默认配置，避免覆盖其他自动配置</p>
     *
     * @return Jackson2ObjectMapperBuilderCustomizer
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> builder
                // 时区：东八区
                .timeZone("Asia/Shanghai")
                // 不序列化 null 字段（减少响应体体积）
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                // 关闭日期作为时间戳输出（统一格式化）
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // LocalDateTime 按统一格式序列化/反序列化
                .modules(javaTimeModule(formatter), longToStringModule());
    }

    /**
     * JavaTime 模块：配置 LocalDateTime 按统一格式序列化与反序列化
     *
     * @param formatter 日期时间格式化器
     * @return JavaTimeModule
     */
    private JavaTimeModule javaTimeModule(DateTimeFormatter formatter) {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(java.time.LocalDateTime.class, new LocalDateTimeSerializer(formatter));
        module.addDeserializer(java.time.LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
        return module;
    }

    /**
     * Long 转 String 自定义模块
     * <p>仅当 Long 值超过 JS 安全整数范围时转为 String，否则保持数字类型</p>
     *
     * @return SimpleModule
     */
    private SimpleModule longToStringModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, new LongToStringSerializer());
        module.addSerializer(Long.TYPE, new LongToStringSerializer());
        return module;
    }

    /**
     * Long 自定义序列化器
     * <p>超过 JS 安全整数范围的 Long 转为 String，避免前端 Number 精度丢失</p>
     */
    private static class LongToStringSerializer extends StdSerializer<Long> {

        public LongToStringSerializer() {
            super(Long.class);
        }

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value != null && (value > JS_SAFE_INTEGER_MAX || value < -JS_SAFE_INTEGER_MAX)) {
                gen.writeString(value.toString());
            } else {
                gen.writeNumber(value);
            }
        }
    }
}
