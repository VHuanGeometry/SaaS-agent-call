package com.vhuan.gateway.filter;

import cn.hutool.core.util.StrUtil;
import com.vhuan.common.constant.HeaderConstants;
import com.vhuan.common.constant.SystemConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 租户上下文注入全局过滤器（Order 1，位于 JWT 认证之后）
 * <p>
 * 确保每个转发到下游的请求都带有 X-Tenant-Id 请求头。
 * 白名单路径（未走 JWT）没有 tenantId 时，回退为系统租户。
 * 注意：必须重建 exchange 才可让请求头修改生效。
 * </p>
 */
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 从请求头获取 tenant_id（AuthGlobalFilter 已注入）
        String tenantId = exchange.getRequest().getHeaders()
                .getFirst(HeaderConstants.TENANT_ID);

        if (StrUtil.isBlank(tenantId)) {
            // 白名单路径可能没有 tenant_id，使用系统租户
            tenantId = SystemConstants.SYSTEM_TENANT_ID;
        }

        // 重建请求对象，确保 X-Tenant-Id 请求头存在并透传给下游服务
        ServerHttpRequest newRequest = exchange.getRequest().mutate()
                .header(HeaderConstants.TENANT_ID, tenantId)
                .build();

        return chain.filter(exchange.mutate().request(newRequest).build());
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
