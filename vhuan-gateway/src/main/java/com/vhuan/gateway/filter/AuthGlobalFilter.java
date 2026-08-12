package com.vhuan.gateway.filter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.vhuan.common.constant.HeaderConstants;
import com.vhuan.gateway.config.GatewaySecurityProperties;
import com.vhuan.gateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * JWT 认证全局过滤器（Order 0，位于白名单放行与租户注入之间）
 * <p>
 * 职责：
 * <ol>
 *   <li>白名单路径直接放行（配置来源：gateway.security.whitelist-paths）</li>
 *   <li>/api/internal/** 内部调用通过 X-Internal-Call Token 校验，不走 JWT</li>
 *   <li>其余请求解析 JWT，并将 userId / tenantId / roles 写入请求头透传给下游</li>
 * </ol>
 * 注意：WebFlux 中修改请求头必须通过 {@code exchange.mutate().request(newRequest).build()}
 * 重建 exchange 才会生效，仅调用 {@code getRequest().mutate()} 不会真正写入。
 * </p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    /** 内部服务调用路径前缀（通过 X-Internal-Call 校验，不走 JWT） */
    private static final String INTERNAL_PREFIX = "/api/internal/";

    /** 内部调用请求头名称 */
    private static final String INTERNAL_CALL_HEADER = "X-Internal-Call";

    /** 用户角色透传请求头名称 */
    private static final String USER_ROLES_HEADER = "X-User-Roles";

    /** 鉴权白名单配置 */
    private final GatewaySecurityProperties securityProperties;

    /** JWT 解析工具 */
    private final JwtUtil jwtUtil;

    public AuthGlobalFilter(GatewaySecurityProperties securityProperties, JwtUtil jwtUtil) {
        this.securityProperties = securityProperties;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单放行
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 内部调用校验
        if (path.startsWith(INTERNAL_PREFIX)) {
            return validateInternalCall(exchange, chain);
        }

        // JWT 校验
        String token = extractToken(exchange);
        if (StrUtil.isBlank(token)) {
            return unauthorized(exchange, "缺少认证 Token");
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            String userId = claims.getSubject();
            String tenantId = claims.get("tenantId", String.class);
            List<String> roles = claims.get("roles", List.class);

            // 重建请求对象，将用户信息写入请求头并透传给下游服务
            ServerHttpRequest newRequest = exchange.getRequest().mutate()
                    .header(HeaderConstants.USER_ID, userId)
                    .header(HeaderConstants.TENANT_ID, tenantId)
                    // roles 可能为空，避免 String.join 抛 NPE
                    .header(USER_ROLES_HEADER, roles != null ? String.join(",", roles) : "")
                    .build();

            return chain.filter(exchange.mutate().request(newRequest).build());
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Token 已过期");
        } catch (JwtException e) {
            return unauthorized(exchange, "无效的 Token");
        }
    }

    /**
     * 判断路径是否命中白名单（前缀匹配）。
     * 白名单从配置文件读取，与路由表保持同步。
     */
    private boolean isWhiteListed(String path) {
        List<String> whitelist = securityProperties.getWhitelistPaths();
        return whitelist != null && whitelist.stream().anyMatch(path::startsWith);
    }

    /**
     * 校验内部服务调用 Token。
     * Token 从 gateway.security.internal-call-token 读取（由环境变量注入），
     * 未配置时默认拒绝并记录 WARN，绝不用可预测的占位符兜底。
     */
    private Mono<Void> validateInternalCall(ServerWebExchange exchange, GatewayFilterChain chain) {
        String expected = securityProperties.getInternalCallToken();
        if (StrUtil.isBlank(expected)) {
            log.warn("[Gateway] 内部调用 Token 未配置，拒绝内部调用路径 {}", exchange.getRequest().getURI().getPath());
            return unauthorized(exchange, "内部调用未开放");
        }

        String actual = exchange.getRequest().getHeaders().getFirst(INTERNAL_CALL_HEADER);
        if (!expected.equals(actual)) {
            return unauthorized(exchange, "非法的内部调用");
        }
        return chain.filter(exchange);
    }

    /**
     * 从 Authorization 请求头提取 Bearer Token。
     */
    private String extractToken(ServerWebExchange exchange) {
        String bearer = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    /**
     * 构造统一的 401 鉴权失败响应，响应结构与业务服务保持一致 {code, message, data}。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = JSONUtil.toJsonStr(
                Map.of("code", 2001, "message", message, "data", null)
        );
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
