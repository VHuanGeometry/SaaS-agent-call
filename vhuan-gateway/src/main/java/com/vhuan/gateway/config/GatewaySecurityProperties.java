package com.vhuan.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关安全配置属性
 * <p>
 * 绑定 application.yml 中 {@code gateway.security} 前缀下的配置项，
 * 使鉴权白名单可从配置文件动态管理，而非硬编码在过滤器里。
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    /** 鉴权白名单路径前缀（无需 JWT 即可访问），支持 Ant 风格通配符，前缀匹配放行 */
    private List<String> whitelistPaths = new ArrayList<>();

    public List<String> getWhitelistPaths() {
        return whitelistPaths;
    }

    public void setWhitelistPaths(List<String> whitelistPaths) {
        this.whitelistPaths = whitelistPaths;
    }
}
