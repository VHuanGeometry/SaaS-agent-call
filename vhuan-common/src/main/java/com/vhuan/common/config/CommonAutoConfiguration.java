package com.vhuan.common.config;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 公共模块自动配置
 * <p>
 * 通过 Spring Boot AutoConfiguration 机制加载（见 META-INF/spring/AutoConfiguration.imports），
 * 注册公共基础设施 Bean：
 * <ul>
 *   <li>{@link Snowflake}：Hutool 雪花 ID 生成器，workerId/datacenterId 从配置注入</li>
 * </ul>
 * 并通过 {@link ComponentScan} 扫描 com.vhuan.common 包下的所有组件（如 JacksonConfig）。
 * </p>
 */
@Configuration
@ComponentScan(basePackages = "com.vhuan.common")
public class CommonAutoConfiguration {

    /** 雪花 ID 的 workerId（K8s StatefulSet 通过 $(POD_INDEX) 环境变量注入） */
    @Value("${snowflake.worker-id:1}")
    private long workerId;

    /** 雪花 ID 的 datacenterId（按可用区分配） */
    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId;

    /**
     * 注册雪花 ID 生成器
     * <p>
     * 使用 Hutool 的 IdUtil.getSnowflake(workerId, datacenterId) 创建，
     * 业务层通过注入 Snowflake 调用 nextIdStr() 获取 String 类型 ID。
     * </p>
     *
     * @return Snowflake 实例
     */
    @Bean
    public Snowflake snowflake() {
        return IdUtil.getSnowflake(workerId, datacenterId);
    }
}
