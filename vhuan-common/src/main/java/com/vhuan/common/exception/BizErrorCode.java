package com.vhuan.common.exception;

/**
 * 业务错误码枚举
 * <p>
 * 错误码按模块区间分配，避免冲突：
 * <ul>
 *   <li>公共：1000-1999（参数校验、通用错误）</li>
 *   <li>auth：2000-2999（认证授权）</li>
 *   <li>tenant：3000-3999（租户管理）</li>
 *   <li>agent：4000-4999（Agent 配置）</li>
 *   <li>campaign：5000-5999（外呼任务）</li>
 *   <li>call：6000-6999（通话管理）</li>
 *   <li>ai-engine：7000-7999（AI 引擎）</li>
 *   <li>contact：8000-8999（客户线索）</li>
 *   <li>analytics：9000-9999（数据分析）</li>
 *   <li>notification：10000-10999（通知）</li>
 *   <li>sip：11000-11999（SIP 连接器）</li>
 * </ul>
 * 各业务模块在自己的枚举中按区间扩展错误码。
 * </p>
 * <p>
 * 注：Graceful Response 3.4.0 未提供错误码枚举接口，
 * 本枚举作为 code/msg 载体，由 {@link BizException} 构造时传入框架的
 * {@code GracefulResponseException(String code, String msg)} 实现动态响应码。
 * </p>
 */
public enum BizErrorCode {

    // ========== 公共错误码 1000-1999 ==========
    /** 操作成功（仅用于显式返回场景，非异常） */
    SUCCESS(0, "操作成功"),
    /** 参数校验失败 */
    PARAM_INVALID(1001, "参数校验失败"),
    /** 资源不存在 */
    RESOURCE_NOT_FOUND(1002, "资源不存在"),
    /** 操作失败（通用业务失败） */
    OPERATION_FAILED(1003, "操作失败"),
    /** 系统内部错误（兜底异常） */
    INTERNAL_ERROR(1004, "系统内部错误");

    private final int code;
    private final String msg;

    BizErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
