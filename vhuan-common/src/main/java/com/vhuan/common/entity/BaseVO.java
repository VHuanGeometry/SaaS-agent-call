package com.vhuan.common.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视图对象基类
 * <p>
 * 与 {@link BaseEntity} 对应，仅暴露前端需要的字段，
 * 不含 deleted、createdBy、updatedBy 等内部字段。
 * </p>
 */
@Data
public class BaseVO {

    /** 主键 ID */
    private String id;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
