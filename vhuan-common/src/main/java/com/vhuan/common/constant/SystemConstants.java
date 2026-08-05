package com.vhuan.common.constant;

/**
 * 系统级常量
 * <p>
 * 统一管理系统级配置常量，如系统租户 ID、默认密码、Token 有效期等。
 * </p>
 */
public final class SystemConstants {

    /** 系统租户 ID（定时任务、系统回调等场景使用） */
    public static final String SYSTEM_TENANT_ID = "system";

    /** 新用户默认密码（首次登录后强制修改） */
    public static final String DEFAULT_PASSWORD = "Vhuan@2024";

    /** Access Token 有效期（分钟） */
    public static final long TOKEN_EXPIRE_MINUTES = 30;

    /** Refresh Token 有效期（天） */
    public static final long REFRESH_TOKEN_EXPIRE_DAYS = 7;

    private SystemConstants() {
        // 常量类，禁止实例化
    }
}
