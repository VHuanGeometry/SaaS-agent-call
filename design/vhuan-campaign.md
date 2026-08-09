# vhuan-campaign 详细设计

> **模块**: vhuan-campaign（外呼任务调度服务）  
> **阶段**: 第二阶段 — 核心业务链路  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供外呼任务的完整生命周期管理：任务创建（绑定 Agent + 话术 + 名单）、批次切分、号码分配策略、调度执行、批量号码下发、重试控制。是核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）的第四环，连接配置层（agent / contact）与执行层（call / ai-engine）。

**职责边界**：
- 任务管理：创建/编辑/启停/暂停/恢复任务，绑定 Agent 与联系人名单
- 批次管理：将大批量号码切分为可调度批次，支持顺序/随机/权重分配策略
- 调度引擎：定时外呼、预测式外呼、预览式外呼三种策略，基于虚拟线程并发调度
- 号码下发：通过 Dubbo Triple CLIENT_STREAM 向 call-service 批量推送号码
- 重试策略：未接通号码的重试次数、间隔、时段限制管理
- 进度追踪：任务执行进度、号码状态汇总、实时统计

**非职责**：
- 不管理 Agent 与话术配置（由 `vhuan-agent` 负责），通过 `@HttpExchange` 拉取 Agent 配置
- 不管理联系人名单数据（由 `vhuan-contact` 负责），通过 `@HttpExchange` 获取名单
- 不校验并发通道配额（由 `vhuan-tenant` 负责），通过 `@HttpExchange` 调用 QuotaApi
- 不发起 SIP 呼叫（由 `vhuan-call` 负责），通过 Dubbo Triple CLIENT_STREAM 下发号码
- 不处理通话过程中的状态流转（由 `vhuan-call` 负责），通过 Kafka 接收通话结果事件

**与 call-service 的分工**：
- **campaign-service 管调度**：决定"什么时候呼、呼谁、呼多少、怎么分配"
- **call-service 管执行**：决定"怎么发起 SIP 呼叫、怎么管理媒体流、怎么跟 ai-engine 交互"
- 号码下发使用 Dubbo Triple CLIENT_STREAM：campaign 作为客户端流式推送号码，call-service 作为服务端逐个接收并处理，最终返回一个汇总响应

---

## 2. 模块结构

```
vhuan-campaign/
├── pom.xml
├── src/main/java/com/vhuan/campaign/
│   ├── CampaignApplication.java                        # 启动类
│   │
│   ├── controller/
│   │   ├── CampaignController.java                      # 任务管理（创建/启停/暂停）
│   │   ├── CampaignBatchController.java                 # 批次查询
│   │   ├── CampaignDetailController.java                # 号码明细查询
│   │   └── CampaignStrategyController.java              # 调度策略配置
│   │
│   ├── service/
│   │   ├── CampaignService.java                         # 任务核心逻辑
│   │   ├── CampaignBatchService.java                    # 批次切分与管理
│   │   ├── CampaignDetailService.java                   # 号码明细管理
│   │   ├── CampaignScheduler.java                       # 调度引擎（核心）
│   │   ├── NumberAllocator.java                         # 号码分配策略
│   │   ├── RetryPolicyService.java                      # 重试策略管理
│   │   ├── CampaignEventConsumer.java                   # Kafka 通话结果消费
│   │   └── impl/
│   │       ├── CampaignServiceImpl.java
│   │       ├── CampaignBatchServiceImpl.java
│   │       ├── CampaignDetailServiceImpl.java
│   │       ├── CampaignSchedulerImpl.java
│   │       ├── NumberAllocatorImpl.java
│   │       ├── RetryPolicyServiceImpl.java
│   │       └── CampaignEventConsumerImpl.java
│   │
│   ├── mapper/
│   │   ├── CampaignMapper.java
│   │   ├── CampaignBatchMapper.java
│   │   ├── CampaignDetailMapper.java
│   │   └── CampaignStrategyMapper.java
│   │
│   ├── entity/
│   │   ├── Campaign.java
│   │   ├── CampaignBatch.java
│   │   ├── CampaignDetail.java
│   │   └── CampaignStrategy.java
│   │
│   ├── dto/
│   │   ├── CampaignCreateRequest.java                   # 创建任务请求
│   │   ├── CampaignUpdateRequest.java
│   │   ├── CampaignStartRequest.java                    # 启动任务请求
│   │   ├── BatchConfigRequest.java                      # 批次配置
│   │   └── RetryPolicyRequest.java                      # 重试策略配置
│   │
│   ├── vo/
│   │   ├── CampaignVO.java
│   │   ├── CampaignDetailVO.java                        # 含执行进度
│   │   ├── CampaignBatchVO.java
│   │   ├── CampaignProgressVO.java                      # 任务进度汇总
│   │   └── CampaignStrategyVO.java
│   │
│   ├── remote/
│   │   ├── AgentClient.java                             # @HttpExchange 调用 agent-service
│   │   ├── ContactClient.java                           # @HttpExchange 调用 contact-service
│   │   ├── QuotaClient.java                             # @HttpExchange 调用 tenant-service
│   │   └── dto/
│   │       ├── AgentSnapshotDTO.java                    # Agent 配置快照（引用 agent 模块定义）
│   │       ├── ContactListDTO.java                      # 联系人名单
│   │       └── QuotaCheckDTO.java
│   │
│   ├── dubbo/
│   │   └── CallDispatchClient.java                      # Dubbo Triple CLIENT_STREAM 客户端
│   │
│   ├── enums/
│   │   ├── CampaignStatus.java                          # 任务状态枚举
│   │   ├── CallDetailStatus.java                        # 号码明细状态枚举
│   │   ├── ScheduleType.java                            # 调度类型枚举
│   │   ├── AllocationStrategy.java                      # 号码分配策略枚举
│   │   └── RetryReason.java                             # 重试原因枚举
│   │
│   └── config/
│       ├── CampaignProperties.java                      # 服务配置
│       ├── SchedulerConfig.java                         # 调度线程池配置
│       └── DubboConfig.java                             # Dubbo 消费者配置
│
└── src/main/resources/
    └── application.yml
```

**包设计要点**：
- `dubbo/` 包封装 Dubbo Triple CLIENT_STREAM 的调用逻辑，`CallDispatchClient` 持有 `@DubboReference` 引用 call-service 暴露的 `CallDispatchApi` 接口
- `remote/` 包是 `@HttpExchange` 客户端，调用 agent / contact / tenant 服务的内部接口
- `enums/` 包集中管理任务与调度的类型枚举

---

## 3. 数据模型设计

所有表落在**租户专属 Schema**（`tenant_{tenantCode}`），与 agent 模块同属租户业务数据。

### 3.1 表关系

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────────┐
│ campaign           │     │ campaign_batch    │     │ campaign_detail       │
│──────────────────│     │──────────────────│     │──────────────────────│
│ id                 │     │ id                │     │ id                    │
│ campaign_name      │     │ campaign_id       │     │ campaign_id           │
│ campaign_code      │     │ batch_no          │     │ batch_id              │
│ agent_id           │     │ total_count       │     │ contact_id             │
│ contact_list_id    │     │ processed_count   │     │ phone                  │
│ strategy_id        │────▶│ succeeded_count   │     │ customer_name          │
│ status             │     │ failed_count       │     │ status                 │
│ total_count        │     │ status            │     │ call_count             │
│ processed_count    │     │ start_time        │     │ last_call_time         │
│ succeeded_count    │     │ end_time          │     │ last_call_result       │
│ failed_count       │     └──────────────────┘     │ retry_reason            │
│ start_time         │                              │ next_retry_time        │
│ end_time           │     ┌──────────────────┐     │ intent_tag             │
│ priority           │     │ campaign_strategy │     │ call_duration          │
│ remark             │     │──────────────────│     │ variables              │
└──────────────────┘     │ id                │     └──────────────────────┘
                          │ campaign_id       │
                          │ schedule_type      │
                          │ allocation_strategy│
                          │ concurrency        │
                          │ time_window_start  │
                          │ time_window_end    │
                          │ max_retry          │
                          │ retry_interval_min │
                          │ max_daily_count     │
                          └──────────────────┘
```

### 3.2 Campaign

```java
@TableName("campaign")
public class Campaign extends BaseEntity {

    /** 任务名称 */
    @Column
    private String campaignName;

    /** 任务编码（租户内唯一） */
    @Column
    private String campaignCode;

    /** 绑定的 Agent ID */
    @Column
    private String agentId;

    /** 联系人名单 ID（引用 contact-service 的名单） */
    @Column
    private String contactListId;

    /** 调度策略 ID */
    @Column
    private String strategyId;

    /** 任务状态（见 CampaignStatus 枚举） */
    @Column
    private String status;

    /** 号码总数 */
    @Column
    private Integer totalCount;

    /** 已处理数 */
    @Column
    private Integer processedCount;

    /** 成功数（接通） */
    @Column
    private Integer succeededCount;

    /** 失败数（未接通/拒接） */
    @Column
    private Integer failedCount;

    /** 任务开始时间 */
    @Column
    private LocalDateTime startTime;

    /** 任务结束时间 */
    @Column
    private LocalDateTime endTime;

    /** 优先级（数字越大优先级越高，调度器按优先级排序） */
    @Column
    private Integer priority;

    /** 备注 */
    @Column
    private String remark;
}
```

### 3.3 CampaignStrategy

```java
@TableName("campaign_strategy")
public class CampaignStrategy extends BaseEntity {

    /** 所属任务 ID */
    @Column
    private String campaignId;

    /** 调度类型（见 ScheduleType 枚举） */
    @Column
    private String scheduleType;

    /** 号码分配策略（见 AllocationStrategy 枚举） */
    @Column
    private String allocationStrategy;

    /** 并发度（同时下发多少个号码给 call-service） */
    @Column
    private Integer concurrency;

    /** 允许外呼时段开始（如 09:00） */
    @Column
    private LocalTime timeWindowStart;

    /** 允许外呼时段结束（如 18:00） */
    @Column
    private LocalTime timeWindowEnd;

    /** 最大重试次数 */
    @Column
    private Integer maxRetry;

    /** 重试间隔（分钟） */
    @Column
    private Integer retryIntervalMin;

    /** 每日最大外呼量（0 表示不限制） */
    @Column
    private Integer maxDailyCount;
}
```

**设计要点**：策略独立为 `CampaignStrategy` 表而非放在 `Campaign` 表中，因为策略参数较多且可被复用（未来支持策略模板）。`concurrency` 控制同时向 call-service 下发多少个号码，与租户套餐的并发通道配额配合使用——调度器在每次下发前先通过 `QuotaApi.checkChannels()` 校验剩余通道。

### 3.4 CampaignBatch

```java
@TableName("campaign_batch")
public class CampaignBatch extends BaseEntity {

    /** 所属任务 ID */
    @Column
    private String campaignId;

    /** 批次序号（从 1 开始） */
    @Column
    private Integer batchNo;

    /** 批次号码总数 */
    @Column
    private Integer totalCount;

    /** 已处理数 */
    @Column
    private Integer processedCount;

    /** 成功数 */
    @Column
    private Integer succeededCount;

    /** 失败数 */
    @Column
    private Integer failedCount;

    /** 批次状态：PENDING / DISPATCHING / COMPLETED / FAILED */
    @Column
    private String status;

    /** 批次开始下发时间 */
    @Column
    private LocalDateTime startTime;

    /** 批次完成时间 */
    @Column
    private LocalDateTime endTime;
}
```

**批次设计**：大批量号码（如 10000 条）按 `concurrency` 参数切分为批次。例如 concurrency=50，则每批 50 个号码通过 Dubbo CLIENT_STREAM 一次性推送给 call-service。批次完成后自动调度下一批，直到所有号码处理完毕或任务暂停。

### 3.5 CampaignDetail

```java
@TableName("campaign_detail")
public class CampaignDetail extends BaseEntity {

    /** 所属任务 ID */
    @Column
    private String campaignId;

    /** 所属批次 ID */
    @Column
    private String batchId;

    /** 联系人 ID（引用 contact-service 的联系人） */
    @Column
    private String contactId;

    /** 电话号码 */
    @Column
    private String phone;

    /** 客户姓名（从联系人冗余，避免跨服务查询） */
    @Column
    private String customerName;

    /** 号码状态（见 CallDetailStatus 枚举） */
    @Column
    private String status;

    /** 已呼叫次数 */
    @Column
    private Integer callCount;

    /** 最近一次呼叫时间 */
    @Column
    private LocalDateTime lastCallTime;

    /** 最近一次呼叫结果（ANSWERED / NO_ANSWER / BUSY / REJECTED / FAILED） */
    @Column
    private String lastCallResult;

    /** 重试原因（见 RetryReason 枚举） */
    @Column
    private String retryReason;

    /** 下次重试时间 */
    @Column
    private LocalDateTime nextRetryTime;

    /** 意向标签（A/B/C/D 类，由 ai-engine 标记后通过 Kafka 回传） */
    @Column
    private String intentTag;

    /** 通话时长（秒） */
    @Column
    private Integer callDuration;

    /** 自定义变量（JSON，从联系人名单带入，供话术变量替换使用） */
    @Column
    private String variables;
}
```

**`variables` 字段设计**：从联系人名单导入时带入的自定义变量（如客户姓名、产品名称等），以 JSON 存储。通话开始时 call-service 会将这些变量与 Agent 的全局变量合并，传递给 ai-engine 用于话术变量替换。

---

## 4. 枚举定义

### 4.1 任务状态（CampaignStatus）

```java
public enum CampaignStatus {
    DRAFT,       // 草稿（编辑中，未启动）
    PENDING,     // 待执行（已启动，等待调度时机）
    RUNNING,     // 执行中（正在调度外呼）
    PAUSED,      // 已暂停（手动暂停，可恢复）
    COMPLETED,   // 已完成（所有号码处理完毕）
    STOPPED      // 已停止（手动终止，未处理的号码不再呼叫）
}
```

### 4.2 号码明细状态（CallDetailStatus）

```java
public enum CallDetailStatus {
    PENDING,        // 待呼叫
    DISPATCHED,     // 已下发（推送到 call-service，等待呼叫结果）
    ANSWERED,       // 已接通（通话已完成）
    NO_ANSWER,      // 无应答
    BUSY,           // 占线
    REJECTED,       // 拒接
    FAILED,         // 呼叫失败（SIP 错误等）
    RETRYING,       // 等待重试
    EXHAUSTED,      // 重试耗尽（已达到最大重试次数，不再重试）
    SKIPPED         // 已跳过（任务停止时未处理的号码）
}
```

### 4.3 调度类型（ScheduleType）

```java
public enum ScheduleType {
    IMMEDIATE,    // 立即外呼（任务启动后立即开始调度）
    SCHEDULED,    // 定时外呼（在指定时间点开始调度）
    PREVIEW       // 预览式外呼（逐条弹出号码，人工确认后外呼）
}
```

### 4.4 号码分配策略（AllocationStrategy）

```java
public enum AllocationStrategy {
    SEQUENTIAL,   // 顺序分配（按导入顺序）
    RANDOM,       // 随机分配（打乱后分配）
    PRIORITY      // 权重分配（按联系人优先级字段排序）
}
```

### 4.5 重试原因（RetryReason）

```java
public enum RetryReason {
    NO_ANSWER,      // 无应答
    BUSY,           // 占线
    REJECTED,       // 拒接
    NETWORK_ERROR,  // 网络错误
    SIP_ERROR,      // SIP 信令错误
    TIMEOUT         // 超时未接通
}
```

---

## 5. 任务生命周期管理

### 5.1 状态机

```
                 ┌──────────┐
   创建任务 ──────▶│  DRAFT    │  草稿，可编辑
                  └────┬─────┘
                       │ 启动
                  ┌────▼─────┐
        ┌────────│ PENDING   │  待执行（定时任务等待调度时机）
        │        └────┬─────┘
        │             │ 到达调度时间 / 立即外呼
        │        ┌────▼─────┐
        │        │ RUNNING   │  执行中
        │        └────┬─────┘
        │             │
        │     ┌───────┼───────┐
        │     │       │       │
        │  暂停│    完成所有   │ 停止
        │     │    号码处理   │
        │     │       │       │
        │ ┌───▼──┐ ┌──▼───┐ ┌─▼──────┐
        │ │PAUSED │ │COMPL │ │STOPPED  │
        │ │       │ │ETED  │ │         │
        │ └───┬──┘ └──────┘ └─────────┘
        │     │ 恢复
        │     ▼
        │  RUNNING 或 PENDING
        │
        └─ 暂停后恢复（恢复到原状态）
```

### 5.2 创建任务流程

```
租户管理员创建外呼任务
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 校验参数                          │  agentId / contactListId 非空
│                                     │  调度参数合法
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 校验 Agent 状态                    │  @HttpExchange 调用 AgentApi
│                                     │  Agent 必须为 PUBLISHED 状态
│                                     │  话术必须为 PUBLISHED 状态
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 拉取联系人名单                     │  @HttpExchange 调用 ContactApi
│                                     │  获取名单内号码总数
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 创建 Campaign 记录                 │  status=DRAFT
│    创建 CampaignStrategy 记录         │  totalCount = 名单号码数
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 5. 导入号码到 CampaignDetail          │  从 contact-service 拉取号码明细
│    （按分配策略排序后写入）            │  SEQUENTIAL / RANDOM / PRIORITY
│    每条记录 status=PENDING            │  variables 从联系人带入
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 6. 切分批次                          │  按 strategy.concurrency 切分
│                                     │  每批写入 CampaignBatch 记录
└──────────────────────────────────────┘
```

### 5.3 启动任务

```
管理员点击"启动"
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 校验当前状态                      │  status=DRAFT 或 PAUSED
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 根据 scheduleType 设置状态        │  IMMEDIATE → status=RUNNING
│                                     │  SCHEDULED → status=PENDING（等待定时触发）
│                                     │  PREVIEW → status=PENDING（等待人工预览）
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 触发调度器                       │  IMMEDIATE: 立即提交调度引擎
│                                     │  SCHEDULED: 注册到定时调度队列
└──────────────────────────────────────┘
```

### 5.4 暂停与恢复

| 操作 | 前置状态 | 目标状态 | 影响 |
|------|----------|----------|------|
| 暂停 | RUNNING | PAUSED | 调度器停止从该任务取新批次；已下发到 call-service 的号码继续执行 |
| 恢复 | PAUSED | RUNNING | 调度器继续从下一个未处理批次开始调度 |
| 停止 | RUNNING / PAUSED | STOPPED | 所有未处理的号码标记为 SKIPPED，不再调度 |

**设计决策**：暂停不影响已下发的号码——已推送到 call-service 的呼叫会继续执行完成。这避免突然中断正在进行的通话，用户体验更好。暂停只阻止后续批次的新号码下发。

---

## 6. 调度引擎设计

### 6.1 调度器架构

```
┌──────────────────────────────────────────────────────┐
│                   CampaignScheduler                     │
│                                                        │
│  ┌──────────────┐    ┌──────────────┐                │
│  │ 定时触发器    │    │ 立即触发器    │                │
│  │ (Scheduled)  │    │ (Immediate)  │                │
│  └──────┬───────┘    └──────┬───────┘                │
│         │                   │                        │
│         └────────┬──────────┘                        │
│                  ▼                                     │
│         ┌────────────────┐                           │
│         │  任务调度队列    │  按优先级排序的待调度任务     │
│         └───────┬────────┘                           │
│                 ▼                                     │
│  ┌──────────────────────────────┐                    │
│  │       批次调度器               │                    │
│  │                              │                    │
│  │  1. 取下一个待处理批次           │                    │
│  │  2. 校验外呼时段               │                    │
│  │  3. 校验租户并发通道配额        │                    │
│  │  4. 通过 Dubbo 下发号码         │                    │
│  │  5. 等待批次完成               │                    │
│  │  6. 调度下一批                 │                    │
│  └──────────────────────────────┘                    │
│                                                        │
│  ┌──────────────────────────────────────┐            │
│  │  虚拟线程池（VirtualThreadPerTask）     │            │
│  │  每个任务的调度在独立虚拟线程中执行       │            │
│  │  不占用平台线程，支持大量并发调度        │            │
│  └──────────────────────────────────────┘            │
└──────────────────────────────────────────────────────┘
```

### 6.2 调度核心逻辑

```java
/**
 * 调度引擎核心接口
 */
public interface CampaignScheduler {

    /**
     * 提交任务到调度队列
     * 在独立虚拟线程中执行，不阻塞调用方
     */
    void schedule(String campaignId);

    /**
     * 暂停任务调度
     * 阻止后续批次下发，已下发的号码继续执行
     */
    void pause(String campaignId);

    /**
     * 恢复任务调度
     */
    void resume(String campaignId);

    /**
     * 停止任务调度
     * 未处理的号码标记为 SKIPPED
     */
    void stop(String campaignId);
}
```

### 6.3 单个任务调度循环

```
任务进入调度队列
        │
        ▼
┌──────────────────────────────────────┐
│ 虚拟线程启动，执行调度循环              │
│ Thread.startVirtualThread(() -> {    │
│   while (task is RUNNING) {          │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 1. 取下一个未处理的批次               │
│    batch.status == PENDING           │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 校验外呼时段                      │
│    当前时间在 timeWindow 范围内？       │
│    否 → 等待到下一个允许时段            │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 校验每日外呼上限                   │
│    今日已呼量 < maxDailyCount？        │
│    否 → 等待到次日                     │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 校验租户并发通道配额                │
│    QuotaApi.checkChannels(tenantId)   │
│    剩余通道数 >= concurrency？          │
│    否 → 等待通道释放（轮询间隔 5s）     │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 5. 通过 Dubbo CLIENT_STREAM 下发号码   │
│    batch.status = DISPATCHING        │
│    detail.status = DISPATCHED        │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 6. 等待批次完成                      │
│    通过 Kafka 事件接收号码结果          │
│    所有号码结果返回后 → batch 完成     │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 7. 更新任务进度                      │
│    processedCount / succeededCount   │
│    failedCount                       │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 8. 检查是否所有批次完成                │
│    是 → status = COMPLETED           │
│    否 → 回到步骤 1（处理下一批）       │
└──────────────────────────────────────┘
```

**虚拟线程设计**：每个 RUNNING 状态的任务在独立虚拟线程中执行调度循环。虚拟线程在等待外呼时段、通道释放、批次完成时会自动挂起（JDK 21 虚拟线程在阻塞 I/O 时让出载体线程），不占用平台线程。即使同时运行 100+ 任务，也不会耗尽线程池。

### 6.4 三种调度策略

**立即外呼（IMMEDIATE）**：

```
任务启动 → status=RUNNING → 立即提交调度队列 → 调度循环开始
```

**定时外呼（SCHEDULED）**：

```
任务启动 → status=PENDING → 注册到 ScheduledExecutor
→ 到达指定时间 → status=RUNNING → 提交调度队列
```

**预览式外呼（PREVIEW）**：

```
任务启动 → status=PENDING → 前端逐条拉取号码
→ 坐席确认后 → 单条号码通过 @HttpExchange 推送到 call-service
→ 等待通话结果 → 拉取下一条
```

预览式外呼不走 Dubbo CLIENT_STREAM 批量下发，而是逐条通过 `@HttpExchange` 调用 call-service 的单个呼叫接口。适用于需要人工审核每个号码的场景。

---

## 7. 号码下发（Dubbo Triple CLIENT_STREAM）

### 7.1 接口契约

根据 AGENTS.md 约束："Dubbo 服务接口（Interface）与 DTO 定义在被调用方服务模块内"。`CallDispatchApi` 接口和 DTO 定义在 **vhuan-call 模块**内，campaign-service 通过 Maven 依赖引用。

> **注意**：以下接口定义是 campaign-service 视角的消费契约，实际定义在 call-service 模块中。vhuan-call 详细设计时会最终确定。

```java
/**
 * 号码下发接口 — Dubbo Triple CLIENT_STREAM
 * 定义在 vhuan-call 模块内，campaign-service 通过 @DubboReference 引用
 */
public interface CallDispatchApi {

    /**
     * 批量下发号码
     * 
     * CLIENT_STREAM 模式：
     * - campaign（客户端）通过返回的 StreamObserver 逐条推送号码
     * - call-service（服务端）接收每条号码并发起 SIP 呼叫
     * - 所有号码推送完成后，call-service 返回一个汇总响应
     *
     * @param responseObserver 响应观察者（接收汇总结果）
     * @return 请求观察者（用于推送号码）
     */
    StreamObserver<CallDispatchRequest> dispatchNumbers(
        StreamObserver<CallDispatchResponse> responseObserver
    );
}
```

### 7.2 DTO 定义

```java
/**
 * 号码下发请求 — 每条对应一个待呼叫号码
 */
public record CallDispatchRequest(
    String detailId,         // campaign_detail ID（结果回传时关联）
    String campaignId,       // 任务 ID
    String agentCode,        // Agent 编码（call-service 据此拉取话术快照）
    String phone,            // 电话号码
    String customerName,     // 客户姓名
    String variables         // 自定义变量（JSON，供话术变量替换）
) {}

/**
 * 批次下发汇总响应 — 所有号码推送完成后返回
 */
public record CallDispatchResponse(
    int totalCount,          // 接收号码总数
    int acceptedCount,       // 接受下发数（通道充足）
    int rejectedCount,       // 拒绝下发数（通道不足/租户暂停等）
    String summary           // 汇总信息
) {}
```

### 7.3 客户端调用逻辑

```java
/**
 * Dubbo CLIENT_STREAM 客户端封装
 */
@Component
public class CallDispatchClient {

    @DubboReference
    private CallDispatchApi callDispatchApi;

    /**
     * 下发一个批次的号码
     */
    public void dispatchBatch(CampaignBatch batch, List<CampaignDetail> details) {
        
        // 1. 创建响应观察者（接收 call-service 的汇总响应）
        StreamObserver<CallDispatchResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(CallDispatchResponse response) {
                // 批次下发完成，更新批次状态
                log.info("批次 {} 下发完成：接收 {}，接受 {}，拒绝 {}",
                    batch.getId(), response.totalCount(),
                    response.acceptedCount(), response.rejectedCount());
                updateBatchResult(batch, response);
            }

            @Override
            public void onError(Throwable t) {
                log.error("批次 {} 下发失败", batch.getId(), t);
                // 失败号码标记为 RETRYING
                markBatchRetry(batch);
            }

            @Override
            public void onCompleted() {
                // 流结束
            }
        };

        // 2. 获取请求观察者，逐条推送号码
        StreamObserver<CallDispatchRequest> requestObserver = 
            callDispatchApi.dispatchNumbers(responseObserver);

        // 3. 流式推送号码
        for (CampaignDetail detail : details) {
            CallDispatchRequest request = new CallDispatchRequest(
                detail.getId(),
                detail.getCampaignId(),
                agentCode,
                detail.getPhone(),
                detail.getCustomerName(),
                detail.getVariables()
            );
            requestObserver.onNext(request);
        }

        // 4. 推送完成
        requestObserver.onCompleted();
    }
}
```

### 7.4 通话结果回传

号码下发后，call-service 发起 SIP 呼叫，通话结果通过 Kafka 事件回传：

```
call-service 发起呼叫
    │
    ├── 通话接通 → ai-engine 接管 → 通话结束
    │
    ├── Kafka 事件 call.ended（携带 detailId, callResult, duration, intentTag）
    │
    ▼
campaign-service Kafka 消费者
    │
    ├── 更新 CampaignDetail 状态
    │   ├── ANSWERED → succeededCount + 1
    │   ├── NO_ANSWER / BUSY / REJECTED / FAILED → 检查重试策略
    │   │   ├── 未达最大重试 → status=RETRYING, 计算下次重试时间
    │   │   └── 已达最大重试 → status=EXHAUSTED, failedCount + 1
    │   └── 更新 lastCallTime, lastCallResult, callDuration, intentTag
    │
    ├── 更新 CampaignBatch 进度
    │   └── processedCount + 1
    │
    └── 更新 Campaign 进度
        └── processedCount + 1
```

---

## 8. 重试策略

### 8.1 重试规则

| 呼叫结果 | 是否重试 | 重试间隔 | 最大重试次数 |
|----------|----------|----------|-------------|
| NO_ANSWER（无应答） | 是 | 30 分钟 | 3 次 |
| BUSY（占线） | 是 | 15 分钟 | 2 次 |
| REJECTED（拒接） | 否 | — | 0 |
| FAILED（SIP 错误） | 是 | 5 分钟 | 2 次 |
| ANSWERED（已接通） | 否 | — | — |

**设计决策**：拒接不重试。拒接说明用户明确不愿意接听，继续呼叫会被标记为骚扰电话，影响线路质量评级。

### 8.2 重试时间计算

```java
/**
 * 计算下次重试时间
 */
LocalDateTime calculateNextRetryTime(CallDetailStatus status, RetryReason reason, 
                                      int currentRetryCount, CampaignStrategy strategy) {
    
    int interval = switch (reason) {
        case NO_ANSWER -> 30;      // 30 分钟
        case BUSY -> 15;            // 15 分钟
        case NETWORK_ERROR, SIP_ERROR -> 5;  // 5 分钟
        default -> strategy.getRetryIntervalMin();  // 使用策略配置的间隔
    };
    
    LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(interval);
    
    // 如果下次重试时间超出当日外呼时段，推到次日时段开始
    if (nextRetry.toLocalTime().isAfter(strategy.getTimeWindowEnd())) {
        nextRetry = nextRetry.toLocalDate().plusDays(1)
            .atTime(strategy.getTimeWindowStart());
    }
    
    return nextRetry;
}
```

### 8.3 重试调度

重试号码不立即回到调度队列，而是由定时任务扫描：

```
定时任务（每分钟执行）
        │
        ▼
┌──────────────────────────────────────┐
│ 查询 status=RETRYING 且              │
│ next_retry_time <= now() 的明细       │
│ AND 所属任务 status=RUNNING          │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 将这些明细重新放入调度批次             │
│ status → PENDING                     │
│ 通过 Dubbo CLIENT_STREAM 重新下发      │
└──────────────────────────────────────┘
```

---

## 9. 对外接口设计

### 9.1 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 任务列表 | GET | `/api/campaign/list` | `campaign:list` | 分页查询 |
| 任务详情 | GET | `/api/campaign/{id}` | `campaign:list` | 含策略、进度 |
| 创建任务 | POST | `/api/campaign` | `campaign:create` | 绑定 Agent + 名单 + 策略 |
| 更新任务 | PUT | `/api/campaign/{id}` | `campaign:update` | 仅 DRAFT 可编辑 |
| 启动任务 | POST | `/api/campaign/{id}/start` | `campaign:start` | |
| 暂停任务 | POST | `/api/campaign/{id}/pause` | `campaign:pause` | |
| 恢复任务 | POST | `/api/campaign/{id}/resume` | `campaign:resume` | |
| 停止任务 | POST | `/api/campaign/{id}/stop` | `campaign:stop` | |
| 任务进度 | GET | `/api/campaign/{id}/progress` | `campaign:list` | 实时执行进度 |
| 批次列表 | GET | `/api/campaign/{id}/batches` | `campaign:list` | |
| 号码明细 | GET | `/api/campaign/{id}/details` | `campaign:list` | 分页，支持状态筛选 |
| 导出明细 | GET | `/api/campaign/{id}/export` | `campaign:list` | 导出号码结果 |
| 预览拉取 | GET | `/api/campaign/{id}/next-number` | `campaign:start` | 预览式外呼：拉取下一条 |
| 预览确认 | POST | `/api/campaign/{id}/confirm-number` | `campaign:start` | 预览式外呼：确认呼叫 |

### 9.2 权限编码

| 权限编码 | 名称 | 默认角色 |
|----------|------|----------|
| `campaign:list` | 查看任务 | TENANT_ADMIN, SUPERVISOR |
| `campaign:create` | 创建任务 | TENANT_ADMIN |
| `campaign:update` | 编辑任务 | TENANT_ADMIN |
| `campaign:start` | 启动任务 | TENANT_ADMIN, SUPERVISOR |
| `campaign:pause` | 暂停任务 | TENANT_ADMIN, SUPERVISOR |
| `campaign:stop` | 停止任务 | TENANT_ADMIN |

---

## 10. 错误码定义（campaign 区间 5000-5999）

新增 `CampaignErrorCode` 枚举，用法与 `BizErrorCode` 一致，通过 `new BizException(CampaignErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 5001 | CAMPAIGN_NOT_FOUND | 任务不存在 | |
| 5002 | CAMPAIGN_CODE_DUPLICATE | 任务编码已存在 | |
| 5003 | CAMPAIGN_NOT_DRAFT | 任务非草稿状态，不可编辑 | 编辑非 DRAFT 状态的任务 |
| 5004 | CAMPAIGN_NOT_RUNNING | 任务未在执行中 | 暂停/停止非 RUNNING 任务 |
| 5005 | CAMPAIGN_ALREADY_RUNNING | 任务已在执行中 | 重复启动 |
| 5006 | CAMPAIGN_ALREADY_STOPPED | 任务已停止 | 操作已停止的任务 |
| 5007 | CAMPAIGN_NO_NUMBERS | 任务无号码 | 名单为空或已全部处理 |
| 5008 | AGENT_NOT_AVAILABLE | Agent 不可用 | Agent 未发布或已停用 |
| 5009 | CONTACT_LIST_EMPTY | 名单为空 | 联系人名单无有效号码 |
| 5010 | QUOTA_INSUFFICIENT | 租户配额不足 | 并发通道不足，且等待超时 |
| 5011 | OUT_OF_TIME_WINDOW | 当前不在外呼时段 | 外呼时间窗口校验失败 |
| 5012 | DAILY_LIMIT_EXCEEDED | 当日外呼量已达上限 | maxDailyCount 限制 |
| 5013 | BATCH_DISPATCH_FAILED | 批次下发失败 | Dubbo 调用异常 |
| 5014 | RETRY_EXHAUSTED | 重试次数已耗尽 | 号码达到最大重试次数 |
| 5015 | CAMPAIGN_DETAIL_NOT_FOUND | 号码明细不存在 | |

---

## 11. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `campaign:running:{tenantId}` | Set(campaignId) | 无 | 当前租户正在执行的任务集合 |
| `campaign:progress:{campaignId}` | Hash | 1h | 任务实时进度缓存（totalCount/processed/succeeded/failed） |
| `campaign:daily_count:{tenantId}:{yyyyMMdd}` | String(int) | 8d | 当日已外呼量（用于 maxDailyCount 限制） |
| `campaign:retry_queue:{tenantId}` | ZSet(detailId, score=nextRetryTimestamp) | 无 | 重试队列（按下次重试时间排序） |

**设计决策**：
- 任务进度使用 Redis Hash 缓存，管理后台轮询进度时直接读 Redis 不查库，减轻数据库压力
- 每日外呼计数器在 Redis 维护，每次成功下发号码后 INCR，凌晨定时任务落库后重置
- 重试队列使用 ZSet，定时任务通过 `ZRANGEBYSCORE 0 now()` 获取到期的重试号码

---

## 12. Kafka 事件

### 12.1 消费的事件

| Topic | 来源 | 消费逻辑 |
|-------|------|----------|
| `call.ended` | call-service | 更新号码明细状态、计算重试、更新任务进度 |
| `call.intent_tagged` | call-service / ai-engine | 更新号码意向标签（A/B/C/D 类） |

**`call.ended` 事件结构**：

```json
{
  "tenantId": "t001",
  "detailId": "d001",
  "campaignId": "c001",
  "batchId": "b001",
  "callResult": "ANSWERED",
  "duration": 120,
  "intentTag": "A",
  "endTime": "2026-08-09T10:30:00"
}
```

### 12.2 发布的事件

| Topic | 消费方 | 说明 |
|-------|--------|------|
| `campaign.created` | analytics-service | 任务创建事件，用于统计 |
| `campaign.started` | analytics-service, notification-service | 任务启动通知 |
| `campaign.completed` | analytics-service, notification-service | 任务完成通知 |
| `campaign.paused` | notification-service | 任务暂停通知 |
| `campaign.stopped` | notification-service | 任务停止通知 |

---

## 13. 依赖与配置

### 13.1 Maven 依赖

```xml
<dependencies>
    <!-- vhuan-common：异常体系、实体基类、常量、租户上下文 -->
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-common</artifactId>
    </dependency>

    <!-- vhuan-call：Dubbo 服务接口（CallDispatchApi）与 DTO 定义在被调用方 -->
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-call-api</artifactId>
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

    <!-- Dubbo（Triple 协议，CLIENT_STREAM 号码下发） -->
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-spring-boot-starter</artifactId>
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

    <!-- Sentinel（@HttpExchange 调用的熔断降级） -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
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
- 引入 `vhuan-call-api`：根据 AGENTS.md 约束，Dubbo 接口 `CallDispatchApi` 与 DTO（`CallDispatchRequest`/`CallDispatchResponse`）定义在 call-service 模块内。call-service 拆分为 `vhuan-call-api`（接口 + DTO，供调用方引用）和 `vhuan-call`（实现）两个 Maven 模块
- 引入 Dubbo：唯一使用 Dubbo 的场景是 CLIENT_STREAM 号码下发，常规 CRUD 走 @HttpExchange
- 引入 Sentinel：对 @HttpExchange 调用 agent / contact / tenant 服务做熔断降级，防止级联故障
- 引入 Kafka：消费通话结果事件、发布任务生命周期事件

### 13.2 application.yml 核心配置

```yaml
server:
  port: 8084

spring:
  application:
    name: vhuan-campaign
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:vhuan}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      group-id: vhuan-campaign
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

# Dubbo 配置
dubbo:
  application:
    name: vhuan-campaign
  protocol:
    name: tri
    port: 20884  # Dubbo 端口与 HTTP 端口分离
  registry:
    address: nacos://${NACOS_ADDR:localhost:8848}?namespace=${NACOS_NAMESPACE:vhuan}
  consumer:
    timeout: 30000  # 号码下发可能耗时较长
    retries: 0       # 流式调用不重试

mybatis-flex:
  mapper-locations: classpath*:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

# 调度引擎配置
campaign:
  # 调度轮询间隔（秒）— 校验通道配额时的轮询频率
  quota-check-interval-seconds: 5
  # 重试扫描间隔（秒）
  retry-scan-interval-seconds: 60
  # 单批次最大号码数
  max-batch-size: 200
  # 默认外呼时段
  default-window-start: "09:00"
  default-window-end: "18:00"
  # 每日外呼上限（0 表示不限制）
  default-max-daily: 0

# 远程服务地址
service:
  agent:
    url: http://vhuan-agent
  contact:
    url: http://vhuan-contact
  tenant:
    url: http://vhuan-tenant
```

---

## 14. 关键流程时序

### 14.1 完整外呼调度链路

```
管理员           campaign-service          tenant-service        call-service       ai-engine
  │                   │                        │                    │                  │
  │ 创建任务          │                        │                    │                  │
  │──────────────────▶│                        │                    │                  │
  │                   │ 校验 Agent              │                    │                  │
  │                   │──getAgentSnapshot──────▶ (agent-service)    │                  │
  │                   │ 拉取名单                │                    │                  │
  │                   │──getContactList────────▶ (contact-service)  │                  │
  │                   │ 创建 Campaign + Detail  │                    │                  │
  │                   │ 切分批次               │                    │                  │
  │◀──────────────────│                        │                    │                  │
  │                   │                        │                    │                  │
  │ 启动任务          │                        │                    │                  │
  │──────────────────▶│                        │                    │                  │
  │                   │ 虚拟线程调度循环启动     │                    │                  │
  │                   │                        │                    │                  │
  │                   │ 校验通道配额             │                    │                  │
  │                   │──checkChannels──────────▶│                    │                  │
  │                   │◀──────────ok───────────│                    │                  │
  │                   │                        │                    │                  │
  │                   │ Dubbo CLIENT_STREAM     │                    │                  │
  │                   │──dispatchNumbers───────▶│───────────────────▶│                  │
  │                   │   (流式推送 N 个号码)    │                    │                  │
  │                   │                        │                    │ 发起 SIP 呼叫     │
  │                   │                        │                    │ 通话接通           │
  │                   │                        │                    │──processCall────▶│
  │                   │                        │                    │   (BIDI STREAM)   │
  │                   │                        │                    │◀─audio + text────│
  │                   │                        │                    │                  │
  │                   │                        │                    │ 通话结束          │
  │                   │                        │                    │──Kafka call.ended│
  │                   │◀────────Kafka call.ended────────────────────│                  │
  │                   │ 更新 Detail 状态        │                    │                  │
  │                   │ 更新进度               │                    │                  │
  │                   │                        │                    │                  │
  │ 查询进度          │                        │                    │                  │
  │──────────────────▶│                        │                    │                  │
  │◀──progress────────│                        │                    │                  │
```

### 14.2 暂停与恢复时序

```
管理员              campaign-service              call-service
  │                      │                           │
  │ 暂停任务             │                           │
  │─────────────────────▶│                           │
  │                      │ status = PAUSED            │
  │                      │ 调度循环退出               │
  │                      │ 不再取新批次               │
  │                      │                           │
  │                      │  已下发的号码继续执行       │
  │                      │  call-service 正常处理     │
  │                      │  通话结果通过 Kafka 回传    │
  │                      │  正常更新 Detail 状态      │
  │                      │                           │
  │ 恢复任务             │                           │
  │─────────────────────▶│                           │
  │                      │ status = RUNNING           │
  │                      │ 新虚拟线程启动调度循环      │
  │                      │ 从下一个未处理批次开始      │
  │                      │──dispatchNumbers──────────▶│
```

---

## 15. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 号码下发方式 | @HttpExchange 批量 vs Dubbo CLIENT_STREAM | **Dubbo CLIENT_STREAM** | 1000+ 号码批量下发，HTTP 请求-响应模型无法承载流式推送；CLIENT_STREAM 支持逐条推送 + 汇总响应 |
| 通话结果回传 | Dubbo 响应流 vs Kafka 事件 | **Kafka 事件** | 通话结束是异步事件，Kafka 解耦且削峰；Dubbo 流用于号码下发方向，结果走 Kafka 更清晰 |
| 调度并发模型 | 线程池 vs 虚拟线程 | **虚拟线程** | 每个任务独立虚拟线程，等待通道/时段时自动挂起，支持 100+ 并发任务调度 |
| 暂停影响范围 | 立即中断所有呼叫 vs 仅停新批次 | **仅停新批次** | 已下发号码继续执行，避免突然中断通话，用户体验更好 |
| 号码明细存储 | 引用 contact-service vs 本地冗余 | **本地冗余** | 通话调度需要频繁查询号码状态，跨服务查询延迟高；名单导入时一次性写入本地 |
| 批次切分粒度 | 固定大小 vs 动态调整 | **按 concurrency 固定切分** | 固定大小便于管理，concurrency 与租户通道配额对齐 |
| 预览式外呼通信 | Dubbo vs @HttpExchange | **@HttpExchange** | 预览式逐条呼叫，非批量场景，HTTP 足够且调试方便 |
| 拒接是否重试 | 重试 vs 不重试 | **不重试** | 拒接说明用户明确不愿接听，继续呼叫影响线路质量评级 |
| 重试调度方式 | 立即回队列 vs 定时扫描 | **定时扫描** | 重试有时间间隔要求，ZSet 按到期时间排序，定时扫描到期号码重新下发 |
| 任务进度查询 | 直接查库 vs Redis 缓存 | **Redis 缓存** | 管理后台高频轮询进度，Redis Hash 缓存减轻数据库压力 |
| 依赖 call-service 的方式 | 直接依赖实现模块 vs 拆分 api 模块 | **拆分 vhuan-call-api** | Dubbo 接口与 DTO 在被调用方定义，调用方只需引用 api 包，不引入实现依赖 |

---

## 16. 自检清单

- [ ] 任务管理：创建/编辑/启动/暂停/恢复/停止，6 种状态完整流转
- [ ] 任务创建时校验 Agent 状态（PUBLISHED）+ 话术状态（PUBLISHED）
- [ ] 名单导入：按分配策略（SEQUENTIAL / RANDOM / PRIORITY）排序后写入 CampaignDetail
- [ ] 批次切分：按 strategy.concurrency 切分，每批通过 Dubbo CLIENT_STREAM 下发
- [ ] 调度引擎：虚拟线程执行调度循环，支持 100+ 并发任务
- [ ] 调度循环：取批次 → 校验时段 → 校验日上限 → 校验通道配额 → Dubbo 下发 → 等待完成
- [ ] 三种调度策略：IMMEDIATE / SCHEDULED / PREVIEW
- [ ] 号码下发：Dubbo Triple CLIENT_STREAM，campaign 流式推送，call-service 汇总响应
- [ ] 通话结果：Kafka 消费 `call.ended`，更新 Detail 状态与任务进度
- [ ] 重试策略：NO_ANSWER 3 次/30min、BUSY 2 次/15min、FAILED 2 次/5min、REJECTED 不重试
- [ ] 重试调度：ZSet 按到期时间排序，定时任务每分钟扫描到期号码
- [ ] 外呼时段校验：超出时段推到次日
- [ ] 每日外呼上限：Redis 计数器，凌晨重置
- [ ] 暂停不影响已下发号码，仅停新批次
- [ ] 对外暴露管理 API（@HttpExchange），Dubbo 接口引用 vhuan-call-api
- [ ] 错误码使用 `CampaignErrorCode`（5000-5999 区间），通过 `BizException` 抛出
- [ ] 数据表落在租户 Schema，由 SchemaManager 初始化
- [ ] Redis 缓存任务进度、每日计数、重试队列
- [ ] Kafka 消费通话结果、发布任务生命周期事件
- [ ] Sentinel 熔断降级 @HttpExchange 调用
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，进入 `vhuan-call` 详细设计。call-service 需定义 `CallDispatchApi`（Dubbo 接口）和 `CallDispatchRequest`/`CallDispatchResponse` DTO，拆分为 `vhuan-call-api` 与 `vhuan-call` 两个 Maven 模块。
