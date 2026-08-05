package com.vhuan.common.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计字段基类
 * <p>
 * 所有需要持久化的业务实体继承此类，统一审计字段与逻辑删除字段。
 * 不含 MyBatis-Flex 注解：vhuan-common 不直接依赖 ORM，由业务模块子类按需添加
 * （如 @Id、@Column(onInsertValue="now()")、@Column(isLogicDelete=true)）。
 * </p>
 */
@Data
public class BaseEntity {

    /** 主键 ID（由 Hutool IdUtil.getSnowflake() 生成，String 类型适配雪花 ID 长度） */
    private String id;

    /** 创建时间（由 MyBatis-Flex InsertListener 或 @Column(onInsertValue="now()") 自动填充） */
    private LocalDateTime createdAt;

    /** 更新时间（由 MyBatis-Flex UpdateListener 或 @Column(onUpdateValue="now()") 自动填充） */
    private LocalDateTime updatedAt;

    /** 创建人 ID（由业务层填充） */
    private String createdBy;

    /** 更新人 ID（由业务层填充） */
    private String updatedBy;

    /** 逻辑删除标识：0=正常，1=已删除（由 MyBatis-Flex @Column(isLogicDelete=true) 处理） */
    private Integer deleted;
}
