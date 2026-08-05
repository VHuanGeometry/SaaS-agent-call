package com.vhuan.common.exception;

import com.feiniaojin.gracefulresponse.GracefulResponseException;

/**
 * 业务异常
 * <p>
 * 继承 Graceful Response 的 {@link GracefulResponseException}，
 * 构造时将 {@link BizErrorCode} 的 code 与 msg 传入父类，
 * 由框架的 GlobalExceptionAdvice 自动捕获并转换为统一响应体，无需自建 GlobalExceptionHandler。
 * </p>
 *
 * <pre>
 * 使用示例：
 *   throw new BizException(BizErrorCode.PARAM_INVALID, "手机号格式错误");
 *   // 响应：{ "code": "1001", "msg": "参数校验失败：手机号格式错误", "data": null }
 * </pre>
 */
public class BizException extends GracefulResponseException {

    /** 关联的错误码枚举，承载原始 code 与 msg，便于业务层捕获后判断 */
    private final BizErrorCode errorCode;

    /**
     * 构造业务异常（无详情）
     *
     * @param errorCode 业务错误码
     */
    public BizException(BizErrorCode errorCode) {
        super(String.valueOf(errorCode.getCode()), errorCode.getMsg());
        this.errorCode = errorCode;
    }

    /**
     * 构造业务异常（带详情，详情拼接到 msg 之后，便于定位问题）
     *
     * @param errorCode 业务错误码
     * @param detail    异常详情（如 "手机号格式错误"）
     */
    public BizException(BizErrorCode errorCode, String detail) {
        super(String.valueOf(errorCode.getCode()), errorCode.getMsg() + "：" + detail);
        this.errorCode = errorCode;
    }

    /**
     * 获取关联的业务错误码
     *
     * @return 错误码枚举
     */
    public BizErrorCode getErrorCode() {
        return errorCode;
    }
}
