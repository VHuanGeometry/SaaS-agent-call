# vhuan-analytics 详细设计

> **模块**: vhuan-analytics（数据分析服务）  
> **阶段**: 第三阶段 — 旁路支撑  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供通话数据的统计分析能力：实时统计（当日通话量、接通率、转化率、意向分布）、离线报表（日报/周报/月报、任务维度、坐席绩效）、大屏数据。为运营决策提供数据支撑。

**职责边界**：
- 实时统计：通过 Kafka 消费通话事件 + Dubbo Triple 指标接收，实时聚合
- 离线报表：日报/周报/月报生成、任务维度报表、坐席绩效分析
- 大屏数据：实时大屏数据接口（通话量、接通率、意向分布等）
- 指标计算：接通率、转化率、平均通话时长、意向分布、挂断率

**非职责**：
- 不管理用户与权限（由 `vhuan-auth` 负责）
- 不处理通话业务逻辑（由 `vhuan-call` 负责），只消费事件做统计
- 不管理外呼任务（由 `vhuan-campaign` 负责），只分析任务数据
- 不发送通知（由 `vhuan-notification` 负责）

**数据来源**：
- Kafka 事件：`call.created`、`call.answered`、`call.ended`、`call.intent_tagged`
- Dubbo Triple 指标：`call-service` 通过 UNARY / CLIENT_STREAM 上报高频指标
- 数据库：从 campaign/contact 等服务读取任务和号码维度数据（通过 @HttpExchange 或共享读库）

---

## 2. 模块结构

```
vhuan-analytics/
├── pom.xml
├── src/main/java/com/vhuan/analytics/
│   ├── AnalyticsApplication.java
│   │
│   ├── controller/
│   │   ├── RealtimeDashboardController.java       # 实时大屏
│   │   ├── ReportController.java                  # 离线报表查询
│   │   └── CampaignAnalyticsController.java       # 任务维度分析
│   │
│   ├── service/
│   │   ├── RealtimeStatService.java               # 实时统计
│   │   ├── MetricAggregateService.java            # 指标聚合
│   │   ├── ReportGenerationService.java           # 离线报表生成
│   │   ├── AgentPerformanceService.java           # 坐席绩效
│   │   ├── KafkaEventConsumer.java               # Kafka 事件消费
│   │   └── impl/
│   │       ├── RealtimeStatServiceImpl.java
│   │       ├── MetricAggregateServiceImpl.java
│   │       ├── ReportGenerationServiceImpl.java
│   │       ├── AgentPerformanceServiceImpl.java
│   │       └── KafkaEventConsumerImpl.java
│   │
│   ├── dubbo/
│   │   └── MetricReceiveServiceImpl.java          # Dubbo Triple 指标接收服务端
│   │
│   ├── mapper/
│   │   ├── ReportDailyMapper.java
│   │   ├── ReportCampaignMapper.java
│   │   └── AgentPerformanceMapper.java
│   │
│   ├── entity/
│   │   ├── ReportDaily.java
│   │   ├── ReportCampaign.java
│   │   └── AgentPerformance.java
│   │
│   ├── vo/
│   │   ├── RealtimeDashboardVO.java               # 实时大屏数据
│   │   ├── ReportDailyVO.java
│   │   ├── ReportCampaignVO.java
│   │   └── AgentPerformanceVO.java
│   │
│   ├── dto/
│   │   ├── MetricDataPoint.java                   # 指标数据点
│   │   ├── MetricReportRequest.java               # 指标上报请求
│   │   └── DashboardQueryRequest.java
│   │
│   ├── enums/
│   │   ├── ReportPeriod.java                      # 报表周期
│   │   └── MetricType.java                        # 指标类型
│   │
│   └── config/
│       └── AnalyticsProperties.java
│
└── src/main/resources/
    └── application.yml
```

---

## 3. 数据模型设计

所有表落在**租户专属 Schema**（`tenant_{tenantCode}`）。

### 3.1 ReportDaily

```java
@TableName("report_daily")
public class ReportDaily extends BaseEntity {

    /** 租户 ID */
    @Column
    private String tenantId;

    /** 统计日期 */
    @Column
    private LocalDate reportDate;

    /** 总呼叫量 */
    @Column
    private Integer totalCalls;

    /** 接通量 */
    @Column
    private Integer answeredCalls;

    /** 接通率（% = answered/total） */
    @Column
    private Double answerRate;

    /** 总通话时长（秒） */
    @Column
    private Long totalDuration;

    /** 平均通话时长（秒） */
    @Column
    private Double avgDuration;

    /** 意向数量（A/B 类） */
    @Column
    private Integer intentCount;

    /** 转化率（% = intent/answered） */
    @Column
    private Double conversionRate;

    /** A 类意向数 */
    @Column
    private Integer intentACount;

    /** B 类意向数 */
    @Column
    private Integer intentBCount;

    /** C 类意向数 */
    @Column
    private Integer intentCCount;

    /** D 类意向数 */
    @Column
    private Integer intentDCount;

    /** 挂断量 */
    @Column
    private Integer hangupCount;

    /** 挂断率（%） */
    @Column
    private Double hangupRate;

    /** 活跃任务数 */
    @Column
    private Integer activeCampaigns;
}
```

### 3.2 ReportCampaign

```java
@TableName("report_campaign")
public class ReportCampaign extends BaseEntity {

    /** 租户 ID */
    @Column
    private String tenantId;

    /** 任务 ID */
    @Column
    private String campaignId;

    /** 任务名称 */
    @Column
    private String campaignName;

    /** 统计日期 */
    @Column
    private LocalDate reportDate;

    /** 总呼叫量 */
    @Column
    private Integer totalCalls;

    /** 接通量 */
    @Column
    private Integer answeredCalls;

    /** 接通率（%） */
    @Column
    private Double answerRate;

    /** 总通话时长（秒） */
    @Column
    private Long totalDuration;

    /** 平均通话时长（秒） */
    @Column
    private Double avgDuration;

    /** 意向数量 */
    @Column
    private Integer intentCount;

    /** 转化率（%） */
    @Column
    private Double conversionRate;

    /** 号码总数 */
    @Column
    private Integer totalNumbers;

    /** 已处理号码数 */
    @Column
    private Integer processedNumbers;

    /** 完成率（%） */
    @Column
    private Double completionRate;
}
```

### 3.3 AgentPerformance

```java
@TableName("agent_performance")
public class AgentPerformance extends BaseEntity {

    /** 租户 ID */
    @Column
    private String tenantId;

    /** 坐席用户 ID */
    @Column
    private String agentUserId;

    /** 坐席姓名 */
    @Column
    private String agentName;

    /** 统计日期 */
    @Column
    private LocalDate reportDate;

    /** 处理通话量 */
    @Column
    private Integer handledCalls;

    /** 介入通话量 */
    @Column
    private Integer interceptCalls;

    /** 平均通话时长（秒） */
    @Column
    private Double avgDuration;

    /** 转人工数量 */
    @Column
    private Integer transferCount;

    /** 坐席通话总时长（秒） */
    @Column
    private Long totalDuration;
}
```

---

## 4. 实时统计

### 4.1 数据接入方式

| 数据源 | 方式 | 说明 |
|--------|------|------|
| 通话生命周期事件 | Kafka | call.created / call.answered / call.ended / call.intent_tagged |
| 高频指标 | Dubbo Triple UNARY / CLIENT_STREAM | 通话中的实时指标（ASR 转写字数、对话轮数等） |

### 4.2 Dubbo Triple 指标接收

根据 AGENTS.md："指标上报 analytics 使用 UNARY（单条）与 CLIENT_STREAM（批量）两种方式"。接口定义在 analytics 模块（被调用方）：

```java
/**
 * 指标上报接口 — Dubbo Triple
 * call-service 通过 @DubboReference 调用
 */
public interface MetricApi {

    /**
     * 单条指标上报（UNARY）
     * 适用于低频、需要即时确认的指标
     */
    void reportMetric(MetricDataPoint dataPoint);

    /**
     * 批量指标上报（CLIENT_STREAM）
     * call-service 批量推送通话指标
     */
    StreamObserver<MetricDataPoint> reportMetricBatch(
        StreamObserver<MetricBatchAck> responseObserver);
}
```

```java
public record MetricDataPoint(
    String tenantId,
    String sessionId,
    String metricType,     // MetricType: ANSWERED/CALL_DURATION/INTENT_TAGGED...
    double value,
    LocalDateTime timestamp
) {}

public record MetricBatchAck(
    int receivedCount,
    String summary
) {}
```

### 4.3 Kafka 实时聚合

```
Kafka 事件流
  call.created / call.answered / call.ended / call.intent_tagged
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 消费事件                           │
│    KafkaEventConsumer                 │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 更新 Redis 实时计数器              │
│    stat:daily:{tenantId}:{date}      │
│    HINCRBY total_calls               │
│    HINCRBY answered_calls            │
│    HINCRBY intent_a / intent_b...    │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 实时大屏接口读取 Redis             │
│    直接返回聚合数据                   │
└──────────────────────────────────────┘
```

### 4.4 实时大屏数据

```java
public record RealtimeDashboardVO(
    int totalCalls,           // 今日总呼叫量
    int answeredCalls,        // 今日接通量
    double answerRate,        // 接通率
    long totalDuration,       // 总通话时长（秒）
    int intentCount,          // 意向数量
    double conversionRate,    // 转化率
    IntentDistribution intentDistribution,  // 意向分布（A/B/C/D）
    List<TrendPoint> hourlyTrend,           // 分时通话趋势
    int activeCalls           // 当前进行中通话数
) {}
```

---

## 5. 离线报表

### 5.1 报表生成流程

```
定时任务（日报每天 01:00，周报周一 01:00，月报每月 1 日 01:00）
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 读取 Redis 当日/周期聚合数据        │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 聚合到报表维度                     │
│    日报 → report_daily               │
│    任务维度 → report_campaign         │
│    坐席绩效 → agent_performance      │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 计算派生指标                       │
│    接通率 = answered/total × 100      │
│    转化率 = intent/answered × 100     │
│    平均时长 = duration/answered       │
│    挂断率 = hangup/answered × 100     │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. UPSERT 写入报表表                  │
│    tenantId + reportDate 唯一         │
└──────────────────────────────────────┘
```

### 5.2 报表类型

| 报表类型 | 周期 | 数据表 | 用途 |
|----------|------|--------|------|
| 日报 | 每天 | report_daily | 每日运营概况 |
| 周报 | 每周 | report_daily 聚合 | 周维度趋势 |
| 月报 | 每月 | report_daily 聚合 | 月度经营分析 |
| 任务报表 | 每天/任务完成 | report_campaign | 单任务效果分析 |
| 坐席绩效 | 每天 | agent_performance | 坐席工作量与介入情况 |

---

## 6. 指标计算口径

### 6.1 核心指标定义

| 指标 | 计算公式 | 数据来源 |
|------|----------|----------|
| 接通率 | 接通量 / 总呼叫量 × 100% | call.answered / call.created |
| 转化率 | 意向数 / 接通量 × 100% | call.intent_tagged（A/B 类） |
| 平均通话时长 | 总时长 / 接通量 | call.ended（duration） |
| 挂断率 | 挂断量 / 接通量 × 100% | call.ended（duration < 30s） |
| 意向分布 | A/B/C/D 类各自占比 | call.intent_tagged |
| 完成率 | 已处理号码 / 号码总数 × 100% | report_campaign |

**挂断判定**：通话时长 < 30s 且结果非主动拒绝视为挂断（客户接通后快速挂断）。实际阈值根据业务测试调优。

---

## 7. 对外接口设计

### 7.1 大屏与管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 实时大屏 | GET | `/api/dashboard/realtime` | `analytics:view` | 实时统计（Redis 读取） |
| 日报查询 | GET | `/api/report/daily` | `analytics:view` | 按日期范围查询 |
| 周报查询 | GET | `/api/report/weekly` | `analytics:view` | 按周聚合 |
| 月报查询 | GET | `/api/report/monthly` | `analytics:view` | 按月聚合 |
| 任务报表 | GET | `/api/report/campaign/{campaignId}` | `analytics:view` | 单任务分析 |
| 坐席绩效 | GET | `/api/report/agent` | `analytics:view` | 坐席绩效报表 |
| 意向分布 | GET | `/api/dashboard/intent-distribution` | `analytics:view` | 意向占比 |
| 分时趋势 | GET | `/api/dashboard/hourly-trend` | `analytics:view` | 分时通话趋势 |
| 导出报表 | GET | `/api/report/export` | `analytics:view` | 导出 Excel |

### 7.2 权限编码

| 权限编码 | 名称 | 默认角色 |
|----------|------|----------|
| `analytics:view` | 查看分析数据 | TENANT_ADMIN, SUPERVISOR |

---

## 8. 错误码定义（analytics 区间 9000-9999）

新增 `AnalyticsErrorCode` 枚举，通过 `new BizException(AnalyticsErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 9001 | REPORT_NOT_FOUND | 报表数据不存在 | 查询无数据日期 |
| 9002 | REPORT_GENERATION_FAILED | 报表生成失败 | 定时任务异常 |
| 9003 | DASHBOARD_DATA_UNAVAILABLE | 大屏数据不可用 | Redis 聚合数据缺失 |
| 9004 | METRIC_RECEIVE_ERROR | 指标接收异常 | Dubbo 指标上报失败 |
| 9005 | DATE_RANGE_INVALID | 日期范围无效 | 起止日期顺序错误/超范围 |
| 9006 | CAMPAIGN_REPORT_NOT_FOUND | 任务报表不存在 | |

---

## 9. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `stat:daily:{tenantId}:{yyyyMMdd}` | Hash | 8d | 当日实时统计（total/answered/intent_a...） |
| `stat:hourly:{tenantId}:{yyyyMMdd}` | ZSet(score=hour) | 8d | 分时通话量（大屏趋势图） |
| `stat:active:{tenantId}` | String(int) | 无 | 当前进行中通话数 |
| `stat:metric:{tenantId}:{metricType}:{yyyyMMdd}` | String | 8d | 指标类型计数 |

---

## 10. 依赖与配置

### 10.1 Maven 依赖

```xml
<dependencies>
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-common</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-processor</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**依赖说明**：
- 引入 Dubbo：作为指标接收服务端（`MetricApi`，UNARY + CLIENT_STREAM）
- 引入 Kafka：消费通话生命周期事件做实时聚合
- 引入 Redisson：Redis 实时统计计数器
- 不依赖 `vhuan-call` 实现模块，通过 Kafka 事件 + Dubbo `MetricApi` 接收数据

### 10.2 application.yml 核心配置

```yaml
server:
  port: 8088

spring:
  application:
    name: vhuan-analytics
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
      group-id: vhuan-analytics
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

dubbo:
  application:
    name: vhuan-analytics
  protocol:
    name: tri
    port: 20888
  registry:
    address: nacos://${NACOS_ADDR:localhost:8848}?namespace=${NACOS_NAMESPACE:vhuan}

mybatis-flex:
  mapper-locations: classpath*:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

analytics:
  # 挂断判定阈值（秒）
  hangup-threshold-seconds: 30
  # 日报生成 cron（每天 01:00）
  daily-report-cron: "0 0 1 * * ?"
  # 周报生成 cron（周一 01:00）
  weekly-report-cron: "0 0 1 ? * 1"
  # 月报生成 cron（每月 1 日 01:00）
  monthly-report-cron: "0 0 1 1 * ?"
```

---

## 11. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 实时统计存储 | 直接写库 vs Redis 聚合 | **Redis 聚合** | 实时大屏高频读取，Redis 计数器毫秒级；数据库用于离线报表 |
| 指标上报方式 | Kafka vs Dubbo Triple | **Dubbo Triple（UNARY + CLIENT_STREAM）** | AGENTS.md 约束，高频指标用 Dubbo 更高效 |
| 实时大屏数据源 | 查库 vs 读 Redis | **读 Redis** | 大屏高频轮询（秒级），避免查库压力 |
| 报表生成 | 实时计算 vs 定时预聚合 | **定时预聚合** | 报表数据量大且不要求实时，定时生成避免高峰计算压力 |
| 挂断判定 | duration < 30s vs 人工标记 | **duration < 30s（可调）** | 客户接通后快速挂断视为无效通话，阈值可配置 |

---

## 12. 自检清单

- [ ] 实时统计：Kafka 消费 call.created/answered/ended/intent_tagged，Redis 聚合
- [ ] Dubbo 指标接收：MetricApi（UNARY 单条 + CLIENT_STREAM 批量）
- [ ] 实时大屏：RealtimeDashboardVO（总呼叫量/接通率/转化率/意向分布/分时趋势/活跃通话）
- [ ] 离线报表：日报/周报/月报定时生成（cron 配置）
- [ ] 任务报表：report_campaign（任务维度，含完成率）
- [ ] 坐席绩效：agent_performance（处理量/介入量/平均时长/转人工数）
- [ ] 指标计算口径：接通率/转化率/平均时长/挂断率/意向分布/完成率
- [ ] 挂断判定：duration < 30s（可配置）
- [ ] Redis 实时计数器：stat:daily / stat:hourly / stat:active / stat:metric
- [ ] 报表 UPSERT：tenantId + reportDate 唯一
- [ ] 错误码使用 `AnalyticsErrorCode`（9000-9999 区间）
- [ ] 数据表落在租户 Schema，3 张表（report_daily/report_campaign/agent_performance）
- [ ] 不依赖 vhuan-call 实现模块，通过 Kafka 事件 + Dubbo MetricApi 接收数据
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，继续 `vhuan-notification` 详细设计。
