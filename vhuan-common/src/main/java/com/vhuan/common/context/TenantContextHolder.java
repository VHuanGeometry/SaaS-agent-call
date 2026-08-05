package com.vhuan.common.context;

import java.lang.ScopedValue;

/**
 * 租户上下文持有者
 * <p>
 * 基于 JDK 21 {@link ScopedValue}（位于 java.lang 包）实现，替代 ThreadLocal：
 * <ul>
 *   <li>生命周期受结构化并发约束，避免虚拟线程场景下的 ThreadLocal 泄漏</li>
 *   <li>不可变绑定，绑定后在整个作用域内可见且不可修改</li>
 * </ul>
 * </p>
 * <p>
 * TODO：ScopedValue 在 JDK 21 为预览特性，需编译/运行时启用 --enable-preview；
 * JDK 24 转正后可移除该标志。
 * </p>
 *
 * <pre>
 * 使用方式（Filter/拦截器/消息消费者）：
 *   ScopedValue.where(TenantContextHolder.getScopedValue(), ctx).run(() -> {
 *       // 业务逻辑，内部任意位置可调用 TenantContextHolder.get()
 *   });
 * </pre>
 */
public final class TenantContextHolder {

    /** 租户上下文的 ScopedValue 实例，由外部通过 where(...) 绑定 */
    private static final ScopedValue<TenantContext> CONTEXT = ScopedValue.newInstance();

    private TenantContextHolder() {
        // 工具类，禁止实例化
    }

    /**
     * 获取 ScopedValue 实例（供 Filter/拦截器/消息消费者使用 where 绑定）
     *
     * @return 租户上下文的 ScopedValue
     */
    public static ScopedValue<TenantContext> getScopedValue() {
        return CONTEXT;
    }

    /**
     * 获取当前作用域内的租户上下文
     * <p>必须在 {@link ScopedValue#where} 绑定的作用域内调用，否则抛出 NoSuchElementException</p>
     *
     * @return 当前租户上下文
     */
    public static TenantContext get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前租户 ID（便捷方法）
     *
     * @return 当前租户 ID
     */
    public static String getTenantId() {
        return CONTEXT.get().tenantId();
    }

    /**
     * 判断当前是否为系统级上下文（定时任务、系统回调等）
     *
     * @return true 表示当前为系统级上下文
     */
    public static boolean isSystem() {
        return "system".equals(getTenantId());
    }
}
