package com.vhuan.common.context;

/**
 * 租户上下文
 * <p>
 * 使用 JDK 21 Record 实现，不可变、线程安全。
 * 承载当前请求/任务所属租户与操作人信息，供全链路（HTTP/Dubbo/Kafka）传递。
 * </p>
 *
 * @param tenantId   租户 ID
 * @param tenantName 租户名称（冗余字段，减少查询）
 * @param planCode   套餐编码（用于配额校验）
 * @param userId     当前用户 ID（可为 null，如定时任务、系统回调）
 * @param userName   当前用户名（可为 null）
 */
public record TenantContext(
        String tenantId,
        String tenantName,
        String planCode,
        Long userId,
        String userName
) {
    /** 系统级上下文：定时任务、系统回调等无具体租户/用户场景使用 */
    public static final TenantContext SYSTEM = new TenantContext(
            "system", "系统", "SYSTEM", null, null
    );
}
