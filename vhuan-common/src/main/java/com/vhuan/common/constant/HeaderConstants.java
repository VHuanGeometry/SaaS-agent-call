package com.vhuan.common.constant;

/**
 * HTTP 请求头常量
 * <p>
 * 统一管理跨服务传递的请求头名称，避免硬编码字符串。
 * 租户上下文在 HTTP 中通过 X-Tenant-Id 请求头传递。
 * </p>
 */
public final class HeaderConstants {

    /** 租户 ID（Gateway 解析 JWT 后注入，各微服务 Filter 读取） */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** 链路追踪 ID（Gateway 生成，全链路传递） */
    public static final String TRACE_ID = "X-Trace-Id";

    /** 当前用户 ID（Gateway 解析 JWT 后注入） */
    public static final String USER_ID = "X-User-Id";

    /** 请求 ID（用于幂等性与请求追踪） */
    public static final String REQUEST_ID = "X-Request-Id";

    private HeaderConstants() {
        // 常量类，禁止实例化
    }
}
