package com.vhuan.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关安全配置属性
 * <p>
 * 绑定配置中心中 {@code gateway.security} 前缀下的配置项（白名单已迁移至 Nacos vhuan-gateway.yaml），
 * 使鉴权白名单可从配置中心动态管理，而非硬编码在过滤器里。
 * </p>
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    /** 鉴权白名单路径前缀（无需 JWT 即可访问），支持 Ant 风格通配符，前缀匹配放行 */
    private List<String> whitelistPaths = new ArrayList<>();

    /** 服务间内部调用的共享 Token（/api/internal/** 校验用），由环境变量注入 */
    private String internalCallToken = "";

    public List<String> getWhitelistPaths() {
        return whitelistPaths;
    }

    public void setWhitelistPaths(List<String> whitelistPaths) {
        this.whitelistPaths = whitelistPaths;
    }

    public String getInternalCallToken() {
        return internalCallToken;
    }

    public void setInternalCallToken(String internalCallToken) {
        this.internalCallToken = internalCallToken;
    }
}
