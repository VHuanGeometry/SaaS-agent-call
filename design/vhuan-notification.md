# vhuan-notification 详细设计

> **模块**: vhuan-notification（通知服务）  
> **阶段**: 第三阶段 — 旁路支撑  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供多通道消息通知能力：站内信、邮件、短信、Webhook 回调。支持消息模板管理、变量替换、发送记录追踪。为核心业务提供服务触达能力（验证码、任务通知、告警等）。

**职责边界**：
- 消息模板：模板管理、变量占位符替换
- 发送渠道：站内信（in-app）、邮件（Email）、短信（SMS）、Webhook 回调
- 触发规则：通话结束通知、任务完成通知、告警通知、验证码发送
- 消息记录：发送历史、状态追踪、失败重试

**非职责**：
- 不生成业务事件（由各业务模块发布 Kafka 事件），只消费事件触发通知
- 不管理用户权限（由 `vhuan-auth` 负责）
- 不直接对接短信运营商（通过短信网关适配器）

**触发方式**：
- Kafka 事件驱动：消费业务模块发布的事件，按触发规则发送通知
- API 主动调用：auth-service 发验证码时通过 @HttpExchange 主动调用

---

## 2. 模块结构

```
vhuan-notification/
├── pom.xml
├── src/main/java/com/vhuan/notification/
│   ├── NotificationApplication.java
│   │
│   ├── controller/
│   │   ├── TemplateController.java               # 模板管理
│   │   ├── MessageController.java                # 消息记录查询
│   │   ├── SendController.java                   # 主动发送 API
│   │   └── ChannelController.java                # 渠道配置
│   │
│   ├── service/
│   │   ├── TemplateService.java                  # 模板管理
│   │   ├── MessageService.java                   # 消息发送与记录
│   │   ├── SendService.java                      # 发送编排
│   │   ├── RetryService.java                     # 失败重试
│   │   ├── KafkaEventConsumer.java               # Kafka 事件消费
│   │   └── channel/
│   │       ├── Channel.java                      # 渠道接口
│   │       ├── InAppChannel.java                 # 站内信渠道
│   │       ├── EmailChannel.java                 # 邮件渠道
│   │       ├── SmsChannel.java                   # 短信渠道
│   │       └── WebhookChannel.java               # Webhook 回调渠道
│   │
│   ├── mapper/
│   │   ├── MessageTemplateMapper.java
│   │   └── MessageRecordMapper.java
│   │
│   ├── entity/
│   │   ├── MessageTemplate.java
│   │   ├── MessageRecord.java
│   │   └── ChannelConfig.java
│   │
│   ├── dto/
│   │   ├── SendMessageRequest.java               # 主动发送请求
│   │   ├── TemplateCreateRequest.java
│   │   └── SendResultVO.java
│   │
│   ├── vo/
│   │   ├── TemplateVO.java
│   │   ├── MessageVO.java
│   │   └── ChannelConfigVO.java
│   │
│   ├── api/
│   │   ├── NotificationApi.java                  # 主动发送接口（供 auth 调用）
│   │   └── QueryApi.java                         # 消息查询接口
│   │
│   ├── enums/
│   │   ├── ChannelType.java                      # 渠道类型
│   │   ├── MessageStatus.java                    # 消息状态
│   │   ├── TemplateCode.java                     # 模板编码
│   │   └── EventTrigger.java                     # 事件触发类型
│   │
│   └── config/
│       ├── NotificationProperties.java
│       └── ChannelConfigProperties.java          # 渠道配置属性
│
└── src/main/resources/
    └── application.yml
```

---

## 3. 数据模型设计

所有表落在**租户专属 Schema**（`tenant_{tenantCode}`）。站内信（in-app）需要按租户隔离，故模板和消息记录都落在租户 Schema。

### 3.1 MessageTemplate

```java
@TableName("message_template")
public class MessageTemplate extends BaseEntity {

    /** 模板编码（唯一，如 SMS_LOGIN_CODE / EMAIL_CAMPAIGN_DONE） */
    @Column
    private String templateCode;

    /** 模板名称 */
    @Column
    private String templateName;

    /** 渠道类型（见 ChannelType 枚举） */
    @Column
    private String channelType;

    /** 邮件主题（邮件渠道） */
    @Column
    private String subject;

    /** 模板内容（支持 ${变量} 占位符） */
    @Column
    private String content;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;

    /** 备注 */
    @Column
    private String remark;
}
```

### 3.2 MessageRecord

```java
@TableName("message_record")
public class MessageRecord extends BaseEntity {

    /** 租户 ID */
    @Column
    private String tenantId;

    /** 渠道类型（IN_APP/EMAIL/SMS/WEBHOOK） */
    @Column
    private String channelType;

    /** 接收人（手机号/邮箱/用户 ID/Webhook URL） */
    @Column
    private String recipient;

    /** 模板编码 */
    @Column
    private String templateCode;

    /** 邮件主题 */
    @Column
    private String subject;

    /** 消息内容（变量替换后） */
    @Column
    private String content;

    /** 消息状态（见 MessageStatus 枚举） */
    @Column
    private String status;

    /** 发送结果信息（失败原因） */
    @Column
    private String resultMsg;

    /** 发送时间 */
    @Column
    private LocalDateTime sendTime;

    /** 重试次数 */
    @Column
    private Integer retryCount;

    /** 关联业务 ID（如 campaignId） */
    @Column
    private String bizId;
}
```

### 3.3 ChannelConfig

```java
@TableName("channel_config")
public class ChannelConfig extends BaseEntity {

    /** 租户 ID（system 表示平台级配置） */
    @Column
    private String tenantId;

    /** 渠道类型（SMS/EMAIL/WEBHOOK） */
    @Column
    private String channelType;

    /** 渠道配置（JSON，如 {"accessKey":"xxx","secret":"yyy","sign":"签名"}） */
    @Column
    private String config;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}
```

---

## 4. 枚举定义

### 4.1 渠道类型（ChannelType）

```java
public enum ChannelType {
    IN_APP,     // 站内信（推送到前端消息中心）
    EMAIL,      // 邮件
    SMS,        // 短信
    WEBHOOK     // Webhook 回调
}
```

### 4.2 消息状态（MessageStatus）

```java
public enum MessageStatus {
    PENDING,      // 待发送
    SENDING,      // 发送中
    SUCCESS,      // 发送成功
    FAILED,       // 发送失败（重试耗尽）
    RETRYING      // 重试中
}
```

### 4.3 模板编码（TemplateCode）

| 模板编码 | 渠道 | 用途 | 内容示例 |
|----------|------|------|----------|
| SMS_LOGIN_CODE | SMS | 登录验证码 | 您的验证码是 ${code}，5 分钟内有效 |
| EMAIL_CAMPAIGN_DONE | EMAIL | 任务完成通知 | 尊敬的用户，外呼任务 ${campaignName} 已执行完成，共呼出 ${totalCalls} 通 |
| SMS_CAMPAIGN_ALERT | SMS | 任务异常告警 | 外呼任务 ${campaignName} 接通率异常偏低（${answerRate}%） |
| IN_APP_CAMPAIGN_DONE | IN_APP | 任务完成站内信 | 外呼任务已执行完成 |
| WEBHOOK_CAMPAIGN_END | WEBHOOK | 任务结束回调 | 回调第三方系统 |
| SMS_TRIAL_EXPIRE | SMS | 试用到期提醒 | 您的试用期将于 ${date} 到期 |

---

## 5. 发送流程

### 5.1 触发方式

| 触发方式 | 说明 | 调用方 |
|----------|------|--------|
| Kafka 事件驱动 | 消费业务事件，按触发规则发送 | campaign/tenant 等发布事件 |
| API 主动调用 | 调用方主动请求发送 | auth-service 发验证码 |

### 5.2 Kafka 事件触发

```
campaign-service 发布 campaign.completed 事件
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 消费事件                           │
│    KafkaEventConsumer                 │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 匹配触发规则                       │
│    campaign.completed → EMAIL_CAMPAIGN_DONE
│                      → IN_APP_CAMPAIGN_DONE
│                      → WEBHOOK_CAMPAIGN_END
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 加载模板 + 变量替换                │
│    查询接收人                         │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 创建 MessageRecord（PENDING）      │
│    提交发送队列                       │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 5. 渠道发送                           │
│    按渠道类型路由到 Channel           │
│    更新 MessageRecord 状态            │
└──────────────────────────────────────┘
```

### 5.3 主动发送 API（供 auth 调用）

```java
/**
 * 通知发送接口 — 供 auth-service 发验证码等主动调用
 */
@HttpExchange(url = "${service.notification.url}", name = "notificationApi")
public interface NotificationApi {

    /**
     * 发送验证码短信
     * @return 发送结果（含 traceId，用于查询状态）
     */
    @PostExchange("/api/internal/send")
    SendResultVO send(@RequestBody SendMessageRequest request);
}
```

```java
public record SendMessageRequest(
    String tenantId,
    String channelType,      // SMS/EMAIL/IN_APP/WEBHOOK
    String templateCode,     // 模板编码
    String recipient,        // 接收人
    Map<String, String> params,  // 模板变量
    String bizId             // 关联业务 ID（可选）
) {}
```

### 5.4 渠道适配器

```java
public interface Channel {

    /** 渠道类型 */
    ChannelType getType();

    /** 发送单条消息 */
    SendResult send(MessageRecord record);

    /** 渠道健康检查 */
    boolean healthCheck();
}
```

| 渠道 | 实现 | 对接方 |
|------|------|--------|
| IN_APP | 写入站内信表 + WebSocket 推送 | 前端消息中心 |
| EMAIL | JavaMailSender | SMTP 服务 |
| SMS | 短信网关适配器 | 阿里云/腾讯云短信 |
| WEBHOOK | RestTemplate 异步回调 | 第三方系统 |

---

## 6. 失败重试

### 6.1 重试策略

| 渠道 | 最大重试次数 | 重试间隔 |
|------|-------------|----------|
| SMS | 3 次 | 1min / 5min / 30min |
| EMAIL | 3 次 | 5min / 15min / 60min |
| WEBHOOK | 5 次 | 1min / 5min / 15min / 30min / 60min |
| IN_APP | 不重试（本地写入） | — |

### 6.2 重试流程

```
消息发送失败
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 更新 MessageRecord 状态           │
│    status = RETRYING                 │
│    retryCount + 1                    │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 计算下次重试时间                   │
│    写入重试队列（ZSet）               │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 定时任务扫描到期消息               │
│    retryCount < maxRetry？            │
└────────────┬─────────────────────────┘
             ▼
         是 → 重新发送
         否 → status = FAILED，记录失败原因
```

---

## 7. 对外接口设计

### 7.1 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 模板列表 | GET | `/api/template/list` | `notification:view` | 分页 |
| 创建模板 | POST | `/api/template` | `notification:manage` | |
| 更新模板 | PUT | `/api/template/{id}` | `notification:manage` | |
| 删除模板 | DELETE | `/api/template/{id}` | `notification:manage` | |
| 消息记录 | GET | `/api/message/list` | `notification:view` | 发送历史分页 |
| 消息详情 | GET | `/api/message/{id}` | `notification:view` | |
| 渠道配置 | GET | `/api/channel/list` | `notification:manage` | |
| 更新渠道配置 | PUT | `/api/channel/{id}` | `notification:manage` | |
| 渠道健康检查 | POST | `/api/channel/{id}/health` | `notification:manage` | |
| 主动发送 | POST | `/api/message/send` | `notification:send` | 手动发送测试 |

### 7.2 权限编码

| 权限编码 | 名称 | 默认角色 |
|----------|------|----------|
| `notification:view` | 查看通知 | TENANT_ADMIN, SUPERVISOR |
| `notification:manage` | 管理通知配置 | TENANT_ADMIN |
| `notification:send` | 发送通知 | TENANT_ADMIN |

---

## 8. 错误码定义（notification 区间 10000-10999）

新增 `NotificationErrorCode` 枚举，通过 `new BizException(NotificationErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 10001 | TEMPLATE_NOT_FOUND | 模板不存在 | |
| 10002 | TEMPLATE_DISABLED | 模板已禁用 | |
| 10003 | CHANNEL_NOT_CONFIGURED | 渠道未配置 | 渠道 config 为空 |
| 10004 | CHANNEL_DISABLED | 渠道已禁用 | |
| 10005 | SEND_FAILED | 消息发送失败 | 渠道返回错误 |
| 10006 | RETRY_EXHAUSTED | 重试次数已耗尽 | |
| 10007 | INVALID_RECIPIENT | 接收人无效 | 邮箱/手机号格式错误 |
| 10008 | RATE_LIMITED | 发送频率超限 | 短信单日发送量超限 |
| 10009 | MESSAGE_NOT_FOUND | 消息记录不存在 | |

---

## 9. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `sms:daily_count:{tenantId}:{yyyyMMdd}` | String(int) | 8d | 租户单日短信发送量（限额） |
| `sms:rate:{recipient}:{yyyyMMdd}` | String(int) | 8d | 单接收人单日短信量（防骚扰） |
| `sms:interval:{recipient}:{type}` | String | 60s | 短信发送间隔（防刷，60s 一条） |
| `notify:retry_queue` | ZSet(msgId, nextRetry) | 无 | 失败重试队列 |

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
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-mail</artifactId>
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
        <groupId>netty-socketio</groupId>
        <artifactId>netty-socketio</artifactId>
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
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**依赖说明**：
- 引入 spring-boot-starter-mail：JavaMailSender 发送邮件
- 引入 Kafka：消费业务事件触发通知
- 引入 netty-socketio：站内信实时推送
- 短信通过抽象 `SmsChannel` 对接，具体运营商（阿里云/腾讯云）通过 ChannelConfig 配置

### 10.2 application.yml 核心配置

```yaml
server:
  port: 8089

spring:
  application:
    name: vhuan-notification
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
      group-id: vhuan-notification
      auto-offset-reset: latest
  mail:
    host: ${MAIL_HOST:smtp.example.com}
    port: ${MAIL_PORT:465}
    username: ${MAIL_USER:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.ssl.enable: true
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
        file-extension: yml

notification:
  # 短信单租户日限额
  sms-daily-limit: 1000
  # 单接收人单日短信量
  sms-recipient-daily-limit: 5
  # 短信发送间隔（秒）
  sms-interval-seconds: 60
  # 重试扫描间隔（秒）
  retry-scan-interval-seconds: 30
```

---

## 11. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 触发方式 | 轮询 vs Kafka 事件 | **Kafka 事件驱动** | 解耦，业务模块只管发布事件，notification 决定怎么通知 |
| 短信对接 | 直连运营商 vs 抽象适配器 | **抽象 SmsChannel 适配器** | 运营商可替换，通过 ChannelConfig 配置切换 |
| 发送线程 | 同步阻塞 vs 异步队列 | **异步发送队列** | 避免阻塞 Kafka 消费者，邮件/短信是慢操作 |
| 失败重试 | 不重试 vs 定时重试 | **定时重试（ZSet）** | 短信/邮件/Webhook 偶发失败，重试提升送达率 |
| 短信限流 | 不限 vs 多维限流 | **三维限流（租户日/接收人日/发送间隔）** | 防骚扰、防刷、防运营商封号 |
| 模板存储 | 代码硬编码 vs 数据库模板 | **数据库模板管理** | 支持运营灵活修改文案，无需发版 |

---

## 12. 自检清单

- [ ] 消息模板：CRUD、编码唯一、${变量} 占位符替换
- [ ] 四渠道：IN_APP / EMAIL / SMS / WEBHOOK 适配器
- [ ] 触发方式：Kafka 事件驱动 + API 主动调用
- [ ] 主动发送接口：NotificationApi.send（auth-service 发验证码用）
- [ ] 模板变量替换：Map<String,String> params 替换模板占位符
- [ ] 消息记录：MessageRecord 全生命周期追踪（PENDING→SENDING→SUCCESS/FAILED/RETRYING）
- [ ] 失败重试：SMS/EMAIL/WEBHOOK 定时重试，IN_APP 不重试
- [ ] 短信限流：租户日限额、接收人日限额、发送间隔（防刷）
- [ ] 渠道配置：ChannelConfig 管理运营商/网关配置
- [ ] 站内信：写入消息表 + WebSocket 推送前端
- [ ] 错误码使用 `NotificationErrorCode`（10000-10999 区间）
- [ ] 数据表落在租户 Schema，3 张表（template/message_record/channel_config）
- [ ] Redis：短信限流计数、重试队列
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，继续 `vhuan-sip-connector` 详细设计——第三阶段最后一个模块。
