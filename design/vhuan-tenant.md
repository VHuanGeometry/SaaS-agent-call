# vhuan-tenant 详细设计

> **模块**: vhuan-tenant（租户管理服务）  
> **阶段**: 第二阶段 — 核心业务链路  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供多租户生命周期管理能力：租户创建（含 Schema 自动初始化）、套餐与配额管理、用量统计与计费、Schema 版本化迁移。是核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）的第二环，为下游所有业务服务提供租户元数据和配额校验基础。

**职责边界**：
- 租户管理：创建租户（自动创建 Schema + 初始化表结构 + 创建管理员）、启用/禁用、信息维护
- 套餐管理：套餐定义、配额模型（并发通道数、存储容量、API 频率、通话分钟数）
- 配额校验：为 `call-service`、`campaign-service` 等提供实时配额查询与预扣减
- 用量统计：通话时长累计、存储用量统计、并发通道占用
- 计费管理：月度账单生成、套餐变更计费
- Schema 管理：租户 Schema 创建、版本化迁移脚本执行

**非职责**：
- 不管理用户与角色（由 `vhuan-auth` 负责），但租户创建时触发管理员账号初始化
- 不直接限流（由 `vhuan-gateway` 的 Sentinel 规则执行），只提供配额数据
- 不处理通话媒体流（由 `vhuan-call` 负责），只提供并发通道配额

**与 auth 的协作**：
- 租户创建时，tenant-service 通过 `@HttpExchange` 调用 auth-service 的用户创建接口，初始化租户管理员账号
- auth-service 校验 Token 后从 `X-Tenant-Id` 读取租户 ID，调用 tenant-service 获取套餐信息（用于 RBAC 数据范围）

---

## 2. 模块结构

```
vhuan-tenant/
├── pom.xml
├── src/main/java/com/vhuan/tenant/
│   ├── TenantApplication.java                # 启动类
│   │
│   ├── controller/
│   │   ├── TenantController.java             # 租户管理（创建/启停/信息维护）
│   │   ├── PlanController.java               # 套餐管理（套餐 CRUD）
│   │   ├── QuotaController.java              # 配额查询（当前用量/剩余配额）
│   │   └── BillController.java               # 账单管理（月度账单/明细）
│   │
│   ├── service/
│   │   ├── TenantService.java                # 租户核心逻辑
│   │   ├── PlanService.java                  # 套餐管理
│   │   ├── QuotaService.java                 # 配额校验与预扣减
│   │   ├── UsageService.java                 # 用量统计
│   │   ├── BillService.java                  # 计费逻辑
│   │   ├── SchemaService.java                # Schema 创建与迁移
│   │   └── impl/
│   │       ├── TenantServiceImpl.java
│   │       ├── PlanServiceImpl.java
│   │       ├── QuotaServiceImpl.java
│   │       ├── UsageServiceImpl.java
│   │       ├── BillServiceImpl.java
│   │       └── SchemaServiceImpl.java
│   │
│   ├── mapper/
│   │   ├── TenantInfoMapper.java
│   │   ├── TenantPlanMapper.java
│   │   ├── TenantPlanQuotaMapper.java
│   │   ├── TenantBillMapper.java
│   │   └── TenantUsageMapper.java
│   │
│   ├── entity/
│   │   ├── TenantInfo.java
│   │   ├── TenantPlan.java
│   │   ├── TenantPlanQuota.java
│   │   ├── TenantBill.java
│   │   └── TenantUsage.java
│   │
│   ├── dto/
│   │   ├── TenantCreateRequest.java          # 创建租户请求
│   │   ├── TenantUpdateRequest.java          # 更新租户信息
│   │   ├── PlanCreateRequest.java            # 创建套餐
│   │   ├── QuotaCheckRequest.java            # 配额校验请求
│   │   └── UsageReportRequest.java           # 用量上报请求
│   │
│   ├── vo/
│   │   ├── TenantVO.java
│   │   ├── PlanVO.java
│   │   ├── QuotaVO.java                      # 当前配额与用量
│   │   ├── BillVO.java
│   │   └── UsageVO.java
│   │
│   ├── remote/
│   │   ├── AuthClient.java                   # @HttpExchange 调用 auth-service
│   │   └── dto/
│   │       └── CreateUserRequest.java        # 初始化管理员账号
│   │
│   ├── api/                                  # 对外暴露的 @HttpExchange 接口（供其他服务依赖）
│   │   ├── TenantApi.java                    # 租户信息查询接口
│   │   └── QuotaApi.java                     # 配额校验接口
│   │
│   ├── schema/
│   │   ├── SchemaManager.java                # Schema 生命周期管理
│   │   ├── SchemaVersionTracker.java         # 版本追踪
│   │   └── script/                           # SQL 脚本（按版本号组织）
│   │       ├── V1__init_schema.sql           # 初始化租户表结构
│   │       └── V2__add_call_slot_table.sql    # 增量迁移脚本
│   │
│   └── config/
│       ├── TenantProperties.java             # 租户服务配置
│       └── SchemaMigrationConfig.java        # Schema 迁移配置
│
└── src/main/resources/
    ├── application.yml
    └── schema-scripts/                       # SQL 脚本资源（打包到 classpath）
        ├── V1__init_schema.sql
        └── V2__add_call_slot_table.sql
```

**包设计要点**：
- `api/` 包中的 `TenantApi`、`QuotaApi` 是 `@HttpExchange` 接口，其他微服务通过 Maven 依赖引用，直接注入调用
- `remote/` 包是 tenant-service 自身调用其他服务（如 auth-service）的 `@HttpExchange` 客户端
- `schema/` 包负责租户 Schema 的 DDL 执行与版本追踪，不使用 Flyway/Liquibase（原因见第 6 节设计决策）

---

## 3. 数据模型设计

所有表落在 `public` Schema（租户元数据共享表），由 tenant-service 统一管理。

### 3.1 表关系

```
┌──────────────┐     ┌──────────────────┐     ┌──────────────────────┐
│ tenant_info   │     │ tenant_plan       │     │ tenant_plan_quota     │
│──────────────│     │──────────────────│     │──────────────────────│
│ id            │     │ id                │     │ id                    │
│ tenant_code   │     │ plan_code         │     │ plan_code             │
│ tenant_name   │     │ plan_name         │     │ quota_key             │
│ plan_code     │────▶│ plan_name_en      │     │ quota_value           │
│ status        │     │ max_channels      │     │ quota_unit             │
│ schema_name   │     │ max_minutes       │     └──────────────────────┘
│ schema_version│     │ max_storage_gb    │
│ contact_name  │     │ api_qps_limit    │
│ contact_phone │     │ price_monthly     │
│ contact_email │     │ price_yearly      │
│ expire_time   │     │ status            │
│ remark        │     └──────────────────┘
└──────────────┘
                        ┌──────────────────────┐
                        │ tenant_usage         │
                        │──────────────────────│
                        │ id                    │
                        │ tenant_id             │
                        │ usage_date            │
                        │ call_minutes          │
                        │ concurrent_channels   │
                        │ storage_used_mb       │
                        │ api_call_count        │
                        └──────────────────────┘

┌──────────────────────┐
│ tenant_bill           │
│──────────────────────│
│ id                    │
│ tenant_id             │
│ bill_period           │
│ plan_code             │
│ base_amount           │
│ overage_minutes       │
│ overage_amount        │
│ storage_overage_gb    │
│ storage_overage_amount│
│ total_amount          │
│ status                │
│ paid_at               │
└──────────────────────┘
```

### 3.2 TenantInfo

```java
@TableName("tenant_info")
public class TenantInfo extends BaseEntity {

    /** 租户编码（唯一，用于 Schema 命名：tenant_{code}） */
    @Column
    private String tenantCode;

    /** 租户名称 */
    @Column
    private String tenantName;

    /** 当前套餐编码（关联 tenant_plan.plan_code） */
    @Column
    private String planCode;

    /** 租户状态：TRIAL=试用, ACTIVE=正常, SUSPENDED=暂停, TERMINATED=终止 */
    @Column
    private String status;

    /** 租户专属 Schema 名称（tenant_{tenantCode}） */
    @Column
    private String schemaName;

    /** Schema 当前版本号（用于迁移追踪） */
    @Column
    private Integer schemaVersion;

    /** 联系人姓名 */
    @Column
    private String contactName;

    /** 联系人电话 */
    @Column
    private String contactPhone;

    /** 联系人邮箱 */
    @Column
    private String contactEmail;

    /** 套餐到期时间（null 表示无期限） */
    @Column
    private LocalDateTime expireTime;

    /** 试用到期时间（试用期内为 TRIAL 状态） */
    @Column
    private LocalDateTime trialExpireTime;

    /** 备注 */
    @Column
    private String remark;
}
```

**表设计要点**：
- `tenantCode` 是业务唯一标识，用于 Schema 命名和 URL 路径，不可修改
- `status` 枚举管理租户生命周期，暂停状态下所有业务接口拒绝请求（Gateway 层拦截）
- `schemaVersion` 追踪每个租户 Schema 的 DDL 版本，用于增量迁移
- 试用期为 7 天，到期后自动转为暂停状态（定时任务检查）

### 3.3 TenantPlan / TenantPlanQuota

```java
@TableName("tenant_plan")
public class TenantPlan extends BaseEntity {

    /** 套餐编码（唯一，如 STARTER / PROFESSIONAL / ENTERPRISE） */
    @Column
    private String planCode;

    /** 套餐名称 */
    @Column
    private String planName;

    /** 套餐英文名 */
    @Column
    private String planNameEn;

    /** 最大并发通道数 */
    @Column
    private Integer maxChannels;

    /** 每月通话分钟数配额 */
    @Column
    private Integer maxMinutes;

    /** 存储容量上限（GB） */
    @Column
    private Integer maxStorageGb;

    /** API 调用频率限制（QPS） */
    @Column
    private Integer apiQpsLimit;

    /** 月费（分） */
    @Column
    private Long priceMonthly;

    /** 年费（分） */
    @Column
    private Long priceYearly;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;

    /** 排序号（套餐展示顺序） */
    @Column
    private Integer sortOrder;
}

@TableName("tenant_plan_quota")
public class TenantPlanQuota extends BaseEntity {

    /** 套餐编码（关联 tenant_plan.plan_code） */
    @Column
    private String planCode;

    /** 配额项编码（如 OVERAGE_MINUTE_PRICE / EXTRA_CHANNEL_PRICE） */
    @Column
    private String quotaKey;

    /** 配额值 */
    @Column
    private String quotaValue;

    /** 配额单位（MINUTE / GB / CHANNEL / TIMES） */
    @Column
    private String quotaUnit;
}
```

**设计决策**：套餐的核心配额（并发通道、通话分钟、存储、API QPS）直接放在 `tenant_plan` 表中作为字段，因为这些是每个套餐必有的核心指标。`tenant_plan_quota` 表用于存储扩展配额项（如超额通话分钟单价、额外通道月费等灵活定价策略），避免频繁修改表结构。

### 3.4 TenantUsage

```java
@TableName("tenant_usage")
public class TenantUsage extends BaseEntity {

    /** 租户 ID */
    @Column
    private String tenantId;

    /** 统计日期（按天聚合） */
    @Column
    private LocalDate usageDate;

    /** 当日通话总分钟数 */
    @Column
    private Integer callMinutes;

    /** 当日峰值并发通道数 */
    @Column
    private Integer maxConcurrentChannels;

    /** 当日存储使用量（MB） */
    @Column
    private Long storageUsedMb;

    /** 当日 API 调用次数 */
    @Column
    private Integer apiCallCount;
}
```

**设计要点**：用量按天聚合存储，实时用量在 Redis 中维护（见第 7 节），每日凌晨定时任务将 Redis 数据落库到 `tenant_usage` 表，并重置 Redis 日计数器。

### 3.5 TenantBill

```java
@TableName("tenant_bill")
public class TenantBill extends BaseEntity {

    /** 租户 ID */
    @Column
    private String tenantId;

    /** 账单周期（格式：yyyyMM） */
    @Column
    private String billPeriod;

    /** 账单生成时的套餐编码 */
    @Column
    private String planCode;

    /** 基础费用（分） */
    @Column
    private Long baseAmount;

    /** 超额通话分钟数 */
    @Column
    private Integer overageMinutes;

    /** 超额通话费用（分） */
    @Column
    private Long overageAmount;

    /** 超额存储（GB） */
    @Column
    private Integer storageOverageGb;

    /** 超额存储费用（分） */
    @Column
    private Long storageOverageAmount;

    /** 总费用（分）= 基础 + 超额通话 + 超额存储 */
    @Column
    private Long totalAmount;

    /** 账单状态：PENDING=待支付, PAID=已支付, OVERDUE=逾期 */
    @Column
    private String status;

    /** 支付时间 */
    @Column
    private LocalDateTime paidAt;
}
```

---

## 4. 租户生命周期管理

### 4.1 状态机

```
                    ┌──────────┐
     创建租户 ──────▶│  TRIAL   │  试用期（7天）
                    └────┬─────┘
                         │ 试用到期 / 手动转正
                    ┌────▼─────┐
                    │  ACTIVE   │  正常使用
                    └────┬─────┘
                         │ 套餐到期 / 欠费 / 违规
                    ┌────▼─────┐
                    │SUSPENDED │  暂停（数据保留，拒绝业务请求）
                    └────┬─────┘
                         │ 恢复
                    ┌────▼─────┐
                    │  ACTIVE   │
                    └────┬─────┘
                         │ 终止合同
                    ┌────▼─────┐
                    │TERMINATED │  终止（Schema 保留 90 天后归档）
                    └──────────┘
```

### 4.2 创建租户流程

```
平台管理员提交创建请求（tenantCode, tenantName, planCode, 管理员信息）
        │
        ▼
┌──────────────────────────────┐
│ 1. 校验参数                  │  tenantCode 格式/唯一性校验
│                             │  planCode 存在性校验
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 2. 创建租户记录              │  tenant_info 插入，status=TRIAL
│    （public Schema 事务）     │  trialExpireTime = now + 7d
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 3. 创建租户 Schema            │  CREATE SCHEMA tenant_{tenantCode}
│    + 初始化表结构             │  执行 V1__init_schema.sql
│    （同一事务内）              │  更新 schema_version = 1
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 4. 初始化管理员账号           │  @HttpExchange 调用 auth-service
│    （通过 AuthClient）        │  创建 TENANT_ADMIN 角色用户
│    失败则回滚整个事务          │  使用默认密码，首次登录强制改密
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 5. 初始化 Redis 配额缓存      │  写入套餐配额到 Redis
│                             │  初始化日用量计数器
└────────────┬────────────────┘
             ▼
       返回 TenantVO
```

**事务设计**：PostgreSQL 支持 DDL 事务，步骤 2-4 在同一个事务中执行。如果管理员账号创建失败，整个事务回滚（Schema 创建也会回滚）。但 `@HttpExchange` 调用 auth-service 是远程调用，不在本地事务范围内——通过**补偿机制**处理：如果远程调用失败，本地事务回滚；如果远程调用成功但本地事务后续失败，则记录一个"待清理"任务，定时任务补偿删除已创建的管理员账号（TODO：实现补偿清理逻辑）。

### 4.3 租户启停

| 操作 | 前置状态 | 目标状态 | 影响 |
|------|----------|----------|------|
| 暂停租户 | ACTIVE | SUSPENDED | Gateway 拦截该租户所有请求（返回 403），进行中的通话不强制中断 |
| 恢复租户 | SUSPENDED | ACTIVE | 恢复正常访问，需检查套餐是否到期 |
| 终止租户 | SUSPENDED | TERMINATED | Schema 保留 90 天，定时任务归档后删除 |
| 试用转正 | TRIAL | ACTIVE | 清除 trialExpireTime，开始正式计费 |

### 4.4 套餐变更

```
租户管理员发起套餐变更（newPlanCode）
        │
        ▼
┌──────────────────────────────┐
│ 1. 校验新套餐存在且已启用      │
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 2. 校验当前用量是否超出新套餐  │  并发通道数、存储容量
│    配额上限                   │  超出则拒绝降级，提示先释放资源
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 3. 更新 tenant_info.plan_code│
│    刷新 Redis 配额缓存         │
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 4. 套餐变更生效               │  立即生效（当月按天比例计费）
│    刷新 Gateway 限流规则       │  Sentinel 规则通过 Nacos 动态推送
└──────────────────────────────┘
```

**设计决策**：套餐变更立即生效而非下月生效。SaaS 电话营销场景下，客户往往因业务突增需要紧急扩容，立即生效的体验更好。计费上按天比例计算差价，在月末账单中体现。

---

## 5. 套餐与配额设计

### 5.1 预置套餐

| 套餐编码 | 套餐名称 | 并发通道 | 月通话分钟 | 存储(GB) | API QPS | 月费(元) | 年费(元) |
|----------|----------|----------|-----------|----------|---------|----------|----------|
| `STARTER` | 入门版 | 5 | 3,000 | 5 | 10 | 299 | 2,990 |
| `PROFESSIONAL` | 专业版 | 20 | 15,000 | 20 | 50 | 999 | 9,990 |
| `ENTERPRISE` | 企业版 | 100 | 100,000 | 100 | 200 | 3,999 | 39,990 |
| `CUSTOM` | 定制版 | — | — | — | — | 面议 | 面议 |

### 5.2 配额模型

配额分两类：

**硬限制（超限直接拒绝）**：

| 配额项 | 校验方 | 校验时机 | 说明 |
|--------|--------|----------|------|
| 并发通道数 | call-service | 发起呼叫前 | 租户当前活跃通话数 ≥ maxChannels 则拒绝 |
| 存储容量 | call-service | 录音上传前 | 租户存储总量 ≥ maxStorageGb 则拒绝新录音 |
| API QPS | gateway | 每次请求 | Sentinel 按 tenant_id 维度限流 |

**软限制（超限计费不阻断）**：

| 配额项 | 统计方 | 超额处理 | 说明 |
|--------|--------|----------|------|
| 通话分钟数 | call-service → tenant-service | 超额部分按单价计费 | 月末账单体现超额费用 |

### 5.3 配额校验接口（供其他服务调用）

通过 `@HttpExchange` 暴露给 `call-service`、`campaign-service` 等：

```java
/**
 * 配额校验接口 — 其他微服务通过 Maven 依赖引用此接口
 * 注入后直接调用，由 Spring 自动生成 HTTP 代理
 */
@HttpExchange(url = "${service.tenant.url}", name = "quotaApi")
public interface QuotaApi {

    /**
     * 校验并发通道是否可用
     * @param tenantId 租户 ID
     * @return true=可分配，false=已达上限
     */
    @PostExchange("/api/internal/quota/check-channels")
    boolean checkChannels(@RequestParam String tenantId);

    /**
     * 预扣减并发通道（通话开始时调用）
     * @param tenantId 租户 ID
     * @return 预扣减凭据 ID（用于释放）
     */
    @PostExchange("/api/internal/quota/acquire-channel")
    String acquireChannel(@RequestParam String tenantId);

    /**
     * 释放并发通道（通话结束时调用）
     * @param tenantId 租户 ID
     * @param credential 预扣减凭据 ID
     */
    @PostExchange("/api/internal/quota/release-channel")
    void releaseChannel(@RequestParam String tenantId, @RequestParam String credential);

    /**
     * 获取租户当前配额与用量
     */
    @GetExchange("/api/internal/quota/{tenantId}")
    QuotaVO getQuota(@PathVariable String tenantId);
}
```

**设计决策**：并发通道校验采用**预扣减模式**（acquire/release），而非实时查询当前并发数。原因：实时查询存在竞态条件——两个并发请求同时查到"当前 19 路、上限 20 路"，都通过校验后实际并发达到 21 路。预扣减通过 Redis 原子操作（INCR + EXPIRE）保证原子性。

---

## 6. Schema 管理

### 6.1 为什么不用 Flyway/Liquibase

Flyway 和 Liquibase 是成熟的数据库迁移工具，但在多租户动态 Schema 场景下存在限制：

| 问题 | Flyway | Liquibase |
|------|--------|-----------|
| Schema 动态创建 | 需预先配置 Schema，不支持运行时动态 Schema | 同左 |
| 多 Schema 并行迁移 | 每个 Schema 独立配置，管理复杂 | 同左 |
| 租户级版本追踪 | 不支持（全局版本表） | 同左 |
| 创建时机 | 启动时自动执行，无法控制租户创建时触发 | 同左 |

**替代方案**：自建轻量级 `SchemaManager`，核心逻辑仅 3 个方法（创建 Schema、执行迁移脚本、追踪版本），SQL 脚本按版本号文件组织，复用 Flyway 的脚本命名规范（`V{version}__{description}.sql`）。

> **不重复造轮子原则的边界**：Flyway/Liquibase 解决的是"单库单 Schema"的迁移问题，本项目的"运行时动态创建 Schema + 租户级版本追踪"是其未覆盖的场景。自建 SchemaManager 仅 100 行左右核心逻辑，不构成"重新实现已有标准库功能"。

### 6.2 SchemaManager 核心设计

```java
@Component
public class SchemaManager {

    /**
     * 创建租户 Schema 并初始化表结构
     * 在租户创建事务中调用，PostgreSQL DDL 支持事务回滚
     */
    void createSchema(String schemaName);

    /**
     * 执行增量迁移脚本
     * 遍历 classpath:/schema-scripts/V*.sql，执行版本号大于当前版本的脚本
     * 每个脚本在独立事务中执行，失败则中止后续脚本
     */
    void migrateSchema(String schemaName, int currentVersion);

    /**
     * 获取最新脚本版本号
     */
    int getLatestVersion();
}
```

### 6.3 迁移流程

```
租户创建 / 定时迁移任务
        │
        ▼
┌──────────────────────────────┐
│ 1. 查询 tenant_info          │  获取 schemaName, schemaVersion
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 2. 扫描 classpath 脚本        │  按 V{version}__{desc}.sql 排序
│    筛选 version > 当前版本    │
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 3. 逐脚本执行                 │  SET search_path TO {schemaName}
│    （每个脚本独立事务）        │  执行 SQL
│                             │  失败 → 中止，记录错误日志
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 4. 更新 schema_version       │  更新 tenant_info.schemaVersion
└──────────────────────────────┘
```

### 6.4 迁移触发时机

| 时机 | 说明 |
|------|------|
| 租户创建 | 执行全部 V1 脚本（初始化表结构） |
| 服务版本升级后 | 定时任务（每天凌晨 02:00）扫描所有租户，执行增量迁移 |
| 手动触发 | 平台管理员通过 API 对指定租户执行迁移 |

### 6.5 初始化脚本（V1__init_schema.sql）

脚本内容即 README 中定义的租户 Schema 核心表 DDL（contact、contact_list、agent_config、campaign、call_session 等），此处不重复列出完整 DDL。脚本中所有表创建在 `SET search_path TO` 指定的 Schema 下。

---

## 7. 用量统计与计费

### 7.1 实时用量统计

实时用量在 Redis 中维护，避免每次上报都写库：

| Redis 键 | 类型 | 说明 |
|----------|------|------|
| `usage:{tenantId}:channels` | String(int) | 当前并发通道数（acquire +1 / release -1） |
| `usage:{tenantId}:minutes:{yyyyMM}` | String(int) | 当月通话分钟数（通话结束时 INCR） |
| `usage:{tenantId}:storage_mb` | String(long) | 当前存储使用量（录音上传后 INCRBY） |
| `usage:{tenantId}:api_count:{yyyyMMdd}` | String(int) | 当日 API 调用次数（Gateway INCR） |
| `quota:{tenantId}:channels` | String(int) | 并发通道配额上限（套餐变更时刷新） |
| `quota:{tenantId}:max_minutes` | String(int) | 月通话分钟配额上限 |
| `quota:{tenantId}:max_storage_mb` | String(long) | 存储配额上限（GB → MB） |

**用量上报链路**：

```
call-service 通话结束
    │
    ├── Kafka 事件 call.ended（携带 tenantId, duration, recordingSize）
    │
    ▼
tenant-service Kafka 消费者
    │
    ├── Redis INCR usage:{tenantId}:minutes:{yyyyMM} += duration
    ├── Redis INCRBY usage:{tenantId}:storage_mb += recordingSize
    └── 若 minutes 超出 max_minutes → 标记超额（不阻断，仅记录）
```

**设计决策**：用量上报通过 Kafka 异步消费而非 `@HttpExchange` 同步调用。原因：通话结束是高频事件，同步调用会拖慢 call-service 的通话结束流程；Kafka 天然解耦并削峰。

### 7.2 每日落库

```
定时任务（每天 00:30 执行）
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 扫描所有 ACTIVE/TRIAL 租户         │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 读取 Redis 当日用量数据            │  api_call_count, max_concurrent_channels
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 写入 tenant_usage 表              │  UPSERT（tenantId + usageDate 唯一）
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 重置 Redis 日计数器               │  api_call_count 归零
│    保留月计数器（minutes 不重置）     │
└──────────────────────────────────────┘
```

### 7.3 月度计费

```
定时任务（每月 1 日 01:00 执行）
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 扫描所有 ACTIVE 租户               │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 读取上月用量                      │  Redis minutes + tenant_usage 聚合
│    计算超额                          │  overage_minutes = max(0, used - max_minutes)
│                                     │  storage_overage = max(0, used_gb - max_storage_gb)
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 计算账单金额                      │  base = 套餐月费
│                                     │  overage_amount = overage_minutes × 单价
│                                     │  storage_overage_amount = storage_overage × 单价
│                                     │  total = base + overage + storage_overage
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 写入 tenant_bill                  │  status = PENDING
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 5. 重置 Redis 月计数器               │  minutes 归零
└──────────────────────────────────────┘
```

**超额计费单价**（存储在 `tenant_plan_quota` 表）：

| 配额项编码 | 套餐 | 值 | 单位 |
|------------|------|----|------|
| `OVERAGE_MINUTE_PRICE` | 所有 | 0.10 | 元/分钟 |
| `STORAGE_OVERAGE_PRICE` | 所有 | 0.50 | 元/GB/月 |
| `EXTRA_CHANNEL_PRICE` | ENTERPRISE | 50 | 元/通道/月 |

---

## 8. 对外接口设计

### 8.1 租户信息查询（@HttpExchange — 供其他服务调用）

```java
@HttpExchange(url = "${service.tenant.url}", name = "tenantApi")
public interface TenantApi {

    /**
     * 根据租户 ID 查询租户基本信息
     * 用于 Gateway / auth-service / call-service 等获取租户状态与套餐
     */
    @GetExchange("/api/internal/tenant/{tenantId}")
    TenantVO getTenant(@PathVariable String tenantId);

    /**
     * 根据租户编码查询
     */
    @GetExchange("/api/internal/tenant/code/{tenantCode}")
    TenantVO getTenantByCode(@PathVariable String tenantCode);

    /**
     * 批量查询租户状态（用于 Gateway 启动时加载缓存）
     */
    @PostExchange("/api/internal/tenant/batch")
    List<TenantVO> batchGetTenants(@RequestBody List<String> tenantIds);

    /**
     * 校验租户状态是否正常（Gateway 鉴权时调用）
     * @return true=租户活跃可访问
     */
    @GetExchange("/api/internal/tenant/{tenantId}/active")
    boolean isTenantActive(@PathVariable String tenantId);
}
```

### 8.2 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 创建租户 | POST | `/api/tenant` | `tenant:create` | 平台管理员操作 |
| 租户列表 | GET | `/api/tenant/list` | `tenant:list` | 分页查询 |
| 租户详情 | GET | `/api/tenant/{id}` | `tenant:list` | 含套餐、用量信息 |
| 更新租户 | PUT | `/api/tenant/{id}` | `tenant:update` | 联系人、备注等 |
| 暂停租户 | PUT | `/api/tenant/{id}/suspend` | `tenant:suspend` | 暂停业务访问 |
| 恢复租户 | PUT | `/api/tenant/{id}/resume` | `tenant:resume` | 恢复业务访问 |
| 终止租户 | PUT | `/api/tenant/{id}/terminate` | `tenant:terminate` | 终止合同 |
| 套餐变更 | PUT | `/api/tenant/{id}/plan` | `tenant:change-plan` | 变更套餐 |
| 套餐列表 | GET | `/api/plan/list` | `plan:list` | 所有套餐 |
| 创建套餐 | POST | `/api/plan` | `plan:create` | 平台管理员 |
| 更新套餐 | PUT | `/api/plan/{id}` | `plan:update` | 平台管理员 |
| 配额查询 | GET | `/api/quota/{tenantId}` | `tenant:list` | 当前用量 + 配额 |
| 账单列表 | GET | `/api/bill/list` | `bill:list` | 按租户/周期查询 |
| 账单详情 | GET | `/api/bill/{id}` | `bill:list` | 含明细 |
| 手动迁移 Schema | POST | `/api/tenant/{id}/migrate` | `tenant:migrate` | 平台管理员 |

---

## 9. 错误码定义（tenant 区间 3000-3999）

新增 `TenantErrorCode` 枚举，用法与 `BizErrorCode` 一致，通过 `new BizException(TenantErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 3001 | TENANT_NOT_FOUND | 租户不存在 | 查询的租户 ID 不存在 |
| 3002 | TENANT_CODE_DUPLICATE | 租户编码已存在 | 创建时编码重复 |
| 3003 | TENANT_SUSPENDED | 租户已暂停 | 租户状态为 SUSPENDED |
| 3004 | TENANT_TERMINATED | 租户已终止 | 租户状态为 TERMINATED |
| 3005 | TENANT_EXPIRED | 租户套餐已到期 | expireTime 已过 |
| 3006 | TENANT_TRIAL_EXPIRED | 试用期已到期 | trialExpireTime 已过 |
| 3007 | PLAN_NOT_FOUND | 套餐不存在 | 查询的 planCode 不存在 |
| 3008 | PLAN_DISABLED | 套餐已停用 | 套餐 status=0 |
| 3009 | QUOTA_CHANNEL_EXCEEDED | 并发通道已达上限 | acquireChannel 时超限 |
| 3010 | QUOTA_STORAGE_EXCEEDED | 存储容量已达上限 | 录音上传时超限 |
| 3011 | QUOTA_MINUTE_EXCEEDED | 通话分钟数已达上限 | 超额（软限制，不阻断，仅告警） |
| 3012 | SCHEMA_CREATE_FAILED | Schema 创建失败 | DDL 执行异常 |
| 3013 | SCHEMA_MIGRATE_FAILED | Schema 迁移失败 | 迁移脚本执行异常 |
| 3014 | DOWNGRADE_QUOTA_EXCEEDED | 当前用量超出目标套餐上限 | 降级套餐时校验失败 |
| 3015 | BILL_ALREADY_EXISTS | 该周期账单已生成 | 重复生成月度账单 |
| 3016 | CREDENTIAL_INVALID | 预扣减凭据无效 | releaseChannel 时凭据不匹配 |

---

## 10. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `usage:{tenantId}:channels` | String(int) | 无 | 当前并发通道数（acquire/release 维护） |
| `usage:{tenantId}:minutes:{yyyyMM}` | String(int) | 35d | 当月通话分钟数（跨月自动过期） |
| `usage:{tenantId}:storage_mb` | String(long) | 无 | 当前存储使用量（MB） |
| `usage:{tenantId}:api_count:{yyyyMMdd}` | String(int) | 8d | 当日 API 调用次数 |
| `usage:{tenantId}:max_channels_day:{yyyyMMdd}` | String(int) | 8d | 当日峰值并发数（每次 acquire 时取 max） |
| `quota:{tenantId}:channels` | String(int) | 无 | 并发通道配额上限 |
| `quota:{tenantId}:max_minutes` | String(int) | 无 | 月通话分钟配额 |
| `quota:{tenantId}:max_storage_mb` | String(long) | 无 | 存储配额上限 |
| `tenant:active:{tenantId}` | String(1) | 5min | 租户活跃状态缓存（Gateway 高频查询） |
| `channel_credential:{credentialId}` | String(tenantId) | 24h | 预扣减凭据 → 租户 ID 映射 |

**设计决策**：
- 配额上限（`quota:*`）在租户创建/套餐变更时写入 Redis，后续配额校验直接读 Redis 不查库
- 租户活跃状态缓存（`tenant:active:*`）5 分钟过期，减少 Gateway 每次请求查库的压力；租户状态变更时主动刷新缓存
- 并发通道凭据 24h 过期，防止通话异常中断后通道不释放（定时任务清理过期凭据）

---

## 11. Kafka 事件

### 11.1 消费的事件

| Topic | 来源 | 消费逻辑 |
|-------|------|----------|
| `call.ended` | call-service | 累计通话分钟、存储用量 |
| `call.recording.ready` | call-service | 累计录音文件大小到存储用量 |
| `tenant.status.changed` | tenant-service 自身 | 状态变更后刷新 Redis 缓存、推送 Gateway 限流规则更新 |

### 11.2 发布的事件

| Topic | 消费方 | 说明 |
|-------|--------|------|
| `tenant.created` | auth-service, notification-service | 租户创建完成通知 |
| `tenant.suspended` | notification-service, gateway | 租户暂停通知 |
| `tenant.plan.changed` | gateway, call-service | 套餐变更，刷新限流规则与配额缓存 |

---

## 12. 依赖与配置

### 12.1 Maven 依赖

```xml
<dependencies>
    <!-- vhuan-common：异常体系、实体基类、常量、租户上下文 -->
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-common</artifactId>
    </dependency>

    <!-- Spring Web（Controller + @HttpExchange） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- 参数校验 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- MyBatis-Flex（ORM + 分页） -->
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-processor</artifactId>
        <scope>provided</scope>
    </dependency>

    <!-- PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Redis（Redisson） -->
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>

    <!-- Kafka -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>

    <!-- Nacos 服务注册与配置 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>

    <!-- Hutool（工具类） -->
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>

    <!-- MapStruct（DTO/VO 转换） -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**依赖说明**：
- 不依赖 `vhuan-auth` 模块，通过 `@HttpExchange` 远程调用 auth-service 的接口（避免模块循环依赖）
- Kafka 用于消费 `call.ended` 事件做用量统计，同时发布租户生命周期事件
- 不引入 Flyway/Liquibase，Schema 管理由自建 `SchemaManager` 负责

### 12.2 application.yml 核心配置

```yaml
server:
  port: 8082

spring:
  application:
    name: vhuan-tenant
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:vhuan}?currentSchema=public
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      group-id: vhuan-tenant
      auto-offset-reset: latest

  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
        file-extension: yml

mybatis-flex:
  mapper-locations: classpath*:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

# 租户服务配置
tenant:
  # 试用期天数
  trial-days: 7
  # Schema 归档保留天数（终止后）
  schema-archive-days: 90
  # Schema 脚本 classpath 路径
  schema-script-location: schema-scripts
  # 账单生成日（每月几号）
  bill-day: 1
  # 用量落库定时任务 cron（每天 00:30）
  usage-persist-cron: "0 30 0 * * ?"
  # 月度计费定时任务 cron（每月 1 日 01:00）
  billing-cron: "0 0 1 1 * ?"

# 远程服务地址
service:
  auth:
    url: http://vhuan-auth
```

---

## 13. 关键流程时序

### 13.1 call-service 并发通道校验

```
call-service                  tenant-service                 Redis
    │                              │                           │
    │  发起呼叫前                   │                           │
    │──acquireChannel(tenantId)──▶│                           │
    │                              │──INCR channels───────────▶│
    │                              │◀─────current=20──────────│
    │                              │                           │
    │                              │  20 <= maxChannels(20)?   │
    │                              │  否 → DECR 回滚           │
    │                              │     抛 QUOTA_CHANNEL_EXCEEDED
    │◀─────BizException────────────│                           │
    │  呼叫被拒绝                   │                           │
    │                              │                           │
    │  是 → 生成 credential         │                           │
    │◀─────credentialId────────────│──SET credential──────────▶│
    │  呼叫继续                     │                           │
    │                              │                           │
    │  通话结束                     │                           │
    │──releaseChannel(cred)───────▶│                           │
    │                              │──DECR channels───────────▶│
    │                              │──DEL credential──────────▶│
    │◀─────ok──────────────────────│                           │
```

### 13.2 试用到期自动暂停

```
定时任务（每天 01:00 扫描）
        │
        ▼
┌──────────────────────────────────┐
│ 查询 status=TRIAL 且              │
│ trialExpireTime < now() 的租户     │
└────────────┬─────────────────────┘
             ▼
┌──────────────────────────────────┐
│ 批量更新 status=SUSPENDED         │
└────────────┬─────────────────────┘
             ▼
┌──────────────────────────────────┐
│ 刷新 Redis tenant:active:* 缓存   │
│ 发布 tenant.suspended 事件        │
│ → notification 发送通知邮件       │
└──────────────────────────────────┘
```

---

## 14. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| Schema 管理工具 | Flyway/Liquibase vs 自建 SchemaManager | **自建 SchemaManager** | 动态 Schema 创建 + 租户级版本追踪是 Flyway 未覆盖场景，自建核心逻辑仅 ~100 行 |
| 用量上报方式 | @HttpExchange 同步 vs Kafka 异步 | **Kafka 异步** | 通话结束是高频事件，同步调用拖慢 call-service；Kafka 削峰解耦 |
| 并发通道校验 | 实时查询 vs 预扣减 | **预扣减（acquire/release）** | 实时查询存在竞态条件，Redis 原子 INCR 保证原子性 |
| 套餐变更生效时机 | 下月生效 vs 立即生效 | **立即生效** | SaaS 电话营销场景需紧急扩容，立即生效体验更好，按天比例计费 |
| 配额数据存储 | 每次查库 vs Redis 缓存 | **Redis 缓存** | 配额校验是高频操作（每次通话发起），Redis 降低数据库压力 |
| 套餐核心配额存储方式 | 扩展表 vs 主表字段 | **主表字段 + 扩展表** | 核心配额（通道/分钟/存储/QPS）必有，放主表避免 JOIN；扩展定价策略放扩展表 |
| 管理员账号创建 | 本地创建 vs 远程调用 auth | **远程调用 auth-service** | 用户管理是 auth 的职责，避免跨模块直接操作 sys_user 表 |
| 远程调用失败补偿 | 2PC vs 补偿任务 | **补偿任务** | 跨服务事务无法用 2PC，记录待清理任务定时补偿 |
| 通话分钟超额处理 | 阻断 vs 计费继续 | **计费继续（软限制）** | 电话营销不能因超分钟中断通话，超额按单价计费 |
| Schema 命名规则 | tenant_{id} vs tenant_{code} | **tenant_{code}** | code 是业务可读标识，便于运维排查；id 为雪花 ID 不直观 |
| 金额存储单位 | 元 vs 分 | **分（Long）** | 避免浮点精度问题，前端展示时除以 100 |

---

## 15. 自检清单

- [ ] 租户创建流程：校验 → 插入记录 → 创建 Schema → 初始化表 → 创建管理员 → 初始化 Redis
- [ ] 租户状态机：TRIAL → ACTIVE → SUSPENDED → TERMINATED 转换规则完整
- [ ] 试用期 7 天自动转 SUSPENDED（定时任务）
- [ ] 套餐变更立即生效，校验降级时当前用量是否超出目标套餐上限
- [ ] 并发通道预扣减（acquire/release）使用 Redis 原子操作，凭据 24h 过期防泄漏
- [ ] 用量上报通过 Kafka 消费 `call.ended` 事件，异步累加 Redis 计数器
- [ ] 每日落库定时任务将 Redis 用量写入 `tenant_usage` 表
- [ ] 月度计费定时任务生成 `tenant_bill`，计算超额费用
- [ ] SchemaManager 支持创建 Schema + 增量迁移 + 版本追踪
- [ ] 配额数据 Redis 缓存，套餐变更时主动刷新
- [ ] 对外暴露 `TenantApi`、`QuotaApi`（@HttpExchange），供其他服务 Maven 依赖引用
- [ ] 错误码使用 `TenantErrorCode`（3000-3999 区间），通过 `BizException` 抛出
- [ ] 金额存储使用分（Long），避免浮点精度
- [ ] 不依赖 `vhuan-auth` 模块，通过 `@HttpExchange` 远程调用
- [ ] 不引入 Flyway/Liquibase，Schema 管理由自建 SchemaManager 负责
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，进入 `vhuan-agent` 详细设计。
