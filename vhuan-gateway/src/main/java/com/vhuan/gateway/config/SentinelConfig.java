package com.vhuan.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * Sentinel 网关限流配置
 * <p>
 * 在应用启动时通过编程方式加载网关维度的限流规则：
 * <ul>
 *   <li>全局 QPS 限制：对平台整体入口做保护</li>
 *   <li>登录接口限流：按客户端 IP 维度，防暴力破解</li>
 * </ul>
 * 租户维度限流规则优先通过 Nacos 数据源动态加载（见 application.yml 中
 * {@code spring.cloud.sentinel.datasource.gw-flow.nacos}），支持热更新，
 * 此处不重复加载，避免与 Nacos 数据源冲突。
 * </p>
 */
@Configuration
public class SentinelConfig {

    @PostConstruct
    public void initRules() {
        // 定义 API 分组（将 URL 匹配规则归一到限流资源名）
        initApiDefinitions();
        // 加载全局限流规则
        initGlobalRules();
        // 接口维度限流规则
        initApiRules();
        // TODO: 租户维度限流由 Nacos 数据源（rule-type=gw-flow）动态加载，暂不在此硬编码
    }

    /**
     * 定义网关 API 分组，将 URL 模式映射为限流资源名。
     * 资源名需与下方 GatewayFlowRule 中引用的资源一致。
     */
    private void initApiDefinitions() {
        Set<ApiDefinition> definitions = new HashSet<>();

        // 全局限流资源：前缀匹配所有请求
        definitions.add(new ApiDefinition("global-api")
                .setPredicateItems(Set.of(
                        new ApiPathPredicateItem()
                                .setPattern("/**")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX)
                )));

        // 登录接口资源：用于防暴力破解限流
        definitions.add(new ApiDefinition("auth-login")
                .setPredicateItems(Set.of(
                        new ApiPathPredicateItem()
                                .setPattern("/api/auth/login")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX)
                )));

        GatewayApiDefinitionManager.loadApiDefinitions(definitions);
    }

    /**
     * 全局限流规则。
     */
    private void initGlobalRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 全局 QPS 限制：10000 QPS，突发流量缓冲 20000
        rules.add(new GatewayFlowRule("global-api")
                .setCount(10000)
                .setIntervalSec(1)
                .setBurst(20000));

        GatewayRuleManager.loadRules(rules);
    }

    /**
     * 接口维度限流规则。
     */
    private void initApiRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 登录接口限流（防暴力破解）：100 次/分钟/IP
        rules.add(new GatewayFlowRule("auth-login")
                .setCount(100)
                .setIntervalSec(60)
                .setParamItem(new GatewayParamFlowItem()
                        .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_CLIENT_IP)));

        GatewayRuleManager.loadRules(rules);
    }
}
