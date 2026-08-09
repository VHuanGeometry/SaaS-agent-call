# vhuan-sip-connector 详细设计

> **模块**: vhuan-sip-connector（SIP 连接器）  
> **阶段**: 第三阶段 — 旁路支撑  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供 SIP 信令与媒体流处理能力：基于 Netty 的 SIP 协议栈、运营商线路管理、RTP 媒体流代理/转发、SIP 信令收发。为 call-service 提供与运营商电话网络对接的底层能力。

**职责边界**：
- SIP 协议栈：基于 Netty 的 SIP 信令处理（Invite/Ringing/Answer/Bye/Cancel）
- 运营商对接：多线路管理、线路健康检查、自动故障切换
- 媒体代理：RTP 媒体流代理/转发、SDP 协商
- 内部接口：供 call-service 调用的内部 API（发起呼叫、挂断、媒体流订阅）
- 呼叫状态通知：通过 Kafka/回调通知 call-service 呼叫状态变化

**非职责**：
- 不做 AI 处理（ASR/NLU/DM/TTS 由 `vhuan-ai-engine` 负责）
- 不管理通话状态机（由 `vhuan-call` 负责），只负责 SIP 信令和媒体流
- 不处理录音（由 `vhuan-call` 负责），只透传媒体流
- 不做外呼调度（由 `vhuan-campaign` 负责）

**与 call-service 的分工**：
- **sip-connector 管通道**：如何与运营商建立电话连接、如何传输媒体
- **call-service 管会话**：通话状态机、AI 交互、录音、坐席介入
- call-service 通过 @HttpExchange 调用 sip-connector 发起呼叫/挂断，sip-connector 通过回调/事件通知 call-service 呼叫状态变化

**网络位置**：sip-connector 是唯一与运营商电话网络直接交互的模块，部署在运营商网络可达的区域，具备独立公网 IP/中继线路。

---

## 2. 模块结构

```
vhuan-sip-connector/
├── pom.xml
├── src/main/java/com/vhuan/sip/
│   ├── SipConnectorApplication.java
│   │
│   ├── controller/
│   │   ├── SipCallController.java              # 呼叫控制 API（供 call-service 调用）
│   │   ├── SipLineController.java              # 线路管理 API
│   │   └── MediaController.java                # 媒体流 API
│   │
│   ├── service/
│   │   ├── SipCallService.java                 # SIP 呼叫逻辑
│   │   ├── SipCallEventPublisher.java          # 呼叫状态事件发布
│   │   ├── LineManager.java                    # 运营商线路管理
│   │   ├── LineHealthChecker.java              # 线路健康检查
│   │   ├── MediaProxyService.java              # 媒体代理
│   │   └── impl/
│   │       ├── SipCallServiceImpl.java
│   │       ├── LineManagerImpl.java
│   │       ├── LineHealthCheckerImpl.java
│   │       └── MediaProxyServiceImpl.java
│   │
│   ├── netty/
│   │   ├── SipServerInitializer.java           # Netty 启动器
│   │   ├── SipMessageDecoder.java              # SIP 消息解码器
│   │   ├── SipMessageEncoder.java              # SIP 消息编码器
│   │   ├── SipHandler.java                     # SIP 信令处理器
│   │   └── TransactionStateMachine.java        # SIP 事务状态机
│   │
│   ├── rtp/
│   │   ├── RtpPacket.java                      # RTP 包
│   │   ├── RtpServer.java                      # RTP 服务器
│   │   ├── RtpForwarder.java                   # RTP 转发器
│   │   └── JitterBuffer.java                   # 抖动缓冲
│   │
│   ├── entity/
│   │   ├── SipLine.java                        # 运营商线路
│   │   └── SipCall.java                        # SIP 呼叫
│   │
│   ├── vo/
│   │   ├── SipCallVO.java
│   │   ├── SipLineVO.java
│   │   └── MediaStreamVO.java
│   │
│   ├── dto/
│   │   ├── SipInviteRequest.java               # 发起呼叫请求
│   │   └── SipHangupRequest.java
│   │
│   ├── enums/
│   │   ├── SipCallStatus.java                  # SIP 呼叫状态
│   │   ├── LineStatus.java                     # 线路状态
│   │   └── SipMethod.java                      # SIP 方法枚举
│   │
│   └── config/
│       ├── SipProperties.java                  # SIP 服务配置
│       └── LineProperties.java                 # 线路配置
│
└── src/main/resources/
    └── application.yml
```

---

## 3. 枚举定义

### 3.1 SIP 方法（SipMethod）

```java
public enum SipMethod {
    INVITE,     // 发起呼叫
    ACK,        // 确认（Invite 的最终响应确认）
    BYE,        // 挂断
    CANCEL,     // 取消（振铃中取消）
    REGISTER,   // 注册（对接运营商）
    OPTIONS,    // 线路探测
    OK,         // 200 OK 响应
    RINGING     // 180 Ringing 响应
}
```

### 3.2 SIP 呼叫状态（SipCallStatus）

```java
public enum SipCallStatus {
    CREATED,      // 已创建
    INVITING,     // 已发送 INVITE
    RINGING,      // 收到 180 Ringing
    ANSWERED,     // 收到 200 OK，媒体协商完成
    IN_PROGRESS,  // 通话中
    ENDING,       // 挂断中
    ENDED         // 已结束
}
```

### 3.3 线路状态（LineStatus）

```java
public enum LineStatus {
    ACTIVE,      // 正常
    DEGRADED,    // 异常（健康检查失败，自动切换）
    DISABLED,    // 手动禁用
    OFFLINE      // 离线
}
```

---

## 4. SIP 协议栈（Netty）

### 4.1 架构

```
                    运营商电话网络
                         │
                         │ SIP/UDP + RTP/UDP
                         │
                    ┌────▼────┐
                    │  Netty  │
                    │  Server │
                    └────┬────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
       ┌──────▼───┐  ┌───▼────┐  ┌──▼───────┐
       │ SIP 信令  │  │  RTP    │  │ 线路管理  │
       │ (SIP/UDP)│  │ 媒体流  │  │ LineManager│
       └──────┬───┘  └───┬────┘  └──────────┘
              │          │
              │   ┌──────▼──────┐
              │   │ MediaProxy  │
              │   │  (转发)     │
              │   └─────────────┘
              │
       ┌──────▼───────────────┐
       │  SipCallEventPublisher│
       │  (Kafka 事件通知)     │
       └──────────────────────┘
```

### 4.2 Netty 配置

```java
@Configuration
public class SipServerInitializer {

    /**
     * Netty 服务器：处理 SIP 信令（UDP）与 RTP 媒体流（UDP）
     */
    @Bean
    public void startSipServer() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(
            Runtime.getRuntime().availableProcessors());

        // SIP 信令通道（UDP）
        NioDatagramChannel sipChannel = ...;  // port 5060
        sipChannel.pipeline()
            .addLast(new SipMessageDecoder())  // 解析 SIP 消息
            .addLast(new SipMessageEncoder())
            .addLast(new SipHandler());        // 处理信令

        // RTP 媒体通道（UDP）
        NioDatagramChannel rtpChannel = ...;   // port 10000-20000
        rtpChannel.pipeline()
            .addLast(new RtpHandler());        // 处理 RTP 包
    }
}
```

### 4.3 SIP 事务状态机

```
发起呼叫（call-service 调用）
        │
        ▼
┌──────────────┐    发送 INVITE
│  INVITING    │─────────────────────────┐
└──────┬───────┘                         │
       │                                 │
       │ 收到 180 Ringing                │
       ▼                                 │
┌──────────────┐                         │
│  RINGING     │                         │
└──────┬───────┘                         │
       │                                 │
       │ 收到 200 OK                     │
       ▼                                 │
┌──────────────┐                         │
│  ANSWERED    │  发送 ACK               │
└──────┬───────┘  媒体协商（SDP）         │
       │                                 │
       │ 通话中                          │
       ▼                                 │
┌──────────────┐                         │
│ IN_PROGRESS  │                         │
└──────┬───────┘                         │
       │ 收到 BYE / 发送 BYE             │
       ▼                                 │
┌──────────────┐                         │
│   ENDED      │◀────────────────────────┘
└──────────────┘
```

---

## 5. 呼叫控制 API（供 call-service 调用）

### 5.1 接口定义

sip-connector 通过 @HttpExchange 暴露内部 API 供 call-service 调用：

```java
/**
 * SIP 呼叫控制接口 — 供 call-service 调用
 */
@HttpExchange(url = "${service.sip.url}", name = "sipApi")
public interface SipApi {

    /**
     * 发起呼叫
     * 
     * @param request 呼叫请求（主叫号码/被叫号码/线路）
     * @return 呼叫信息（sipCallId，call-service 保存用于挂断）
     */
    @PostExchange("/api/internal/call/invite")
    SipCallVO invite(@RequestBody SipInviteRequest request);

    /**
     * 挂断呼叫
     */
    @PostExchange("/api/internal/call/hangup")
    void hangup(@RequestBody SipHangupRequest request);

    /**
     * 查询呼叫状态
     */
    @GetExchange("/api/internal/call/{sipCallId}")
    SipCallVO getCall(@PathVariable String sipCallId);

    /**
     * 订阅媒体流（返回 RTP 端点信息，供 call-service 接入音频）
     */
    @PostExchange("/api/internal/call/{sipCallId}/subscribe-media")
    MediaStreamVO subscribeMedia(@PathVariable String sipCallId);

    /**
     * 线路列表
     */
    @GetExchange("/api/internal/line/list")
    List<SipLineVO> listLines();
}
```

### 5.2 请求/响应 DTO

```java
/**
 * 发起呼叫请求
 */
public record SipInviteRequest(
    String sessionId,        // 关联的通话会话 ID
    String callerNumber,     // 主叫号码（线路分配）
    String calleeNumber,     // 被叫号码
    String lineId,           // 指定线路（可选，null 时自动分配）
    Integer timeoutSeconds   // 呼叫超时
) {}

/**
 * 挂断请求
 */
public record SipHangupRequest(
    String sipCallId,        // SIP 呼叫 ID
    String sessionId         // 通话会话 ID
) {}

/**
 * 呼叫信息
 */
public record SipCallVO(
    String sipCallId,        // SIP 呼叫 ID
    String sessionId,        // 通话会话 ID
    String callerNumber,
    String calleeNumber,
    String lineId,           // 使用的线路
    String status,           // SIP 呼叫状态
    String sdpOffer,         // 本地 SDP（媒体协商）
    String rtpEndpoint       // RTP 端点（ip:port）
) {}
```

---

## 6. 运营商线路管理

### 6.1 线路数据模型

```java
@TableName("sip_line")
public class SipLine extends BaseEntity {

    /** 线路名称 */
    @Column
    private String lineName;

    /** 运营商 */
    @Column
    private String provider;

    /** 主叫号码（线路归属号码） */
    @Column
    private String callerNumber;

    /** SIP 服务器地址 */
    @Column
    private String sipServer;

    /** 线路认证账号 */
    @Column
    private String authUser;

    /** 线路认证密码 */
    @Column
    private String authPassword;

    /** 最大并发通道数 */
    @Column
    private Integer maxChannels;

    /** 当前占用通道数 */
    @Column
    private Integer usedChannels;

    /** 线路状态（见 LineStatus 枚举） */
    @Column
    private String status;

    /** 权重（负载均衡，数字越大分配越多） */
    @Column
    private Integer weight;
}
```

### 6.2 线路分配策略

```
call-service 发起呼叫
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 查询可用线路                       │
│    status=ACTIVE 且 usedChannels < maxChannels│
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 按权重负载均衡选择线路             │
│    加权轮询（weight 越大分配越多）     │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 占用线路通道                       │
│    usedChannels + 1                  │
│    返回 lineId + callerNumber        │
└──────────────────────────────────────┘
```

### 6.3 线路健康检查

```
定时任务（每 30s 执行）
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 遍历所有线路                       │
│    发送 OPTIONS 探测                  │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 判断线路状态                       │
│    3 次连续失败 → status=DEGRADED     │
│    成功 → status=ACTIVE              │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 自动故障切换                       │
│    线路 DEGRADED → 从可用池移除       │
│    新呼叫分配到其他 ACTIVE 线路        │
│    恢复后重新加入                     │
└──────────────────────────────────────┘
```

---

## 7. RTP 媒体流管理

### 7.1 媒体代理架构

```
                    ┌──────────────┐
                    │ 运营商 RTP    │
                    │  媒体端点     │
                    └──────┬───────┘
                           │ RTP/UDP
                    ┌──────▼───────┐
                    │  sip-connector│
                    │  MediaProxy   │
                    │              │
                    │  RTP 接收     │
                    │  JitterBuffer │
                    │  抖动缓冲     │
                    │  RTP 转发     │
                    └──────┬───────┘
                           │
                           │ 经 Dubbo BIDI STREAM 传给 call-service
                           │ → ai-engine
                           ▼
                    ┌──────────────┐
                    │ call-service  │
                    │  → ai-engine  │
                    └──────────────┘
```

### 7.2 媒体流处理

```
RTP 包到达 sip-connector
        │
        ▼
┌──────────────────────────────────────┐
│ 1. JitterBuffer 抖动缓冲              │
│    消除网络抖动，平滑音频              │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. RTP → PCM 转换                    │
│    解 G.711/G.729 编解码              │
│    输出 PCM 16kHz 16bit mono         │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 推送 PCM 给 call-service          │
│    通过 @HttpExchange 回调            │
│    或共享内存/内部队列                 │
└──────────────────────────────────────┘
```

**设计决策**：sip-connector 将 RTP 音频转成 PCM 后，通过内部接口推送给 call-service。call-service 再经 Dubbo BIDI STREAM 传给 ai-engine。为降低链路延迟，sip-connector 与 call-service 建议同机房部署，通过内网低延迟传输。

### 7.3 反向媒体流

```
ai-engine TTS 音频
        │
        ▼
call-service（经 Dubbo BIDI STREAM 接收）
        │
        ▼
┌──────────────────────────────────────┐
│ sip-connector 接收 PCM                │
│ PCM → RTP 编码                        │
│ 打 RTP 包头（时间戳/序号）             │
│ 发送到运营商媒体端点                  │
└──────────────────────────────────────┘
```

---

## 8. 呼叫状态事件发布

sip-connector 通过 Kafka 发布呼叫状态事件，call-service 消费后更新通话状态机：

| Topic | 触发时机 | 说明 |
|-------|----------|------|
| `sip.call.ringing` | 收到 180 Ringing | 振铃中 |
| `sip.call.answered` | 收到 200 OK | 接通（媒体协商完成） |
| `sip.call.failed` | 收到 4xx/5xx/超时 | 呼叫失败 |
| `sip.call.ended` | 收到/发送 BYE | 通话结束 |

```java
/**
 * SIP 呼叫状态事件
 */
public record SipCallEvent(
    String tenantId,
    String sessionId,       // 关联的通话会话 ID
    String sipCallId,       // SIP 呼叫 ID
    String eventType,       // RINGING/ANSWERED/FAILED/ENDED
    String reason,          // 失败原因（FAILED 时）
    LocalDateTime timestamp
) {}
```

---

## 9. 对外接口设计

### 9.1 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 线路列表 | GET | `/api/line/list` | `sip:manage` | |
| 添加线路 | POST | `/api/line` | `sip:manage` | |
| 更新线路 | PUT | `/api/line/{id}` | `sip:manage` | |
| 禁用线路 | PUT | `/api/line/{id}/disable` | `sip:manage` | |
| 手动健康检查 | POST | `/api/line/{id}/health` | `sip:manage` | |
| 线路状态总览 | GET | `/api/line/overview` | `sip:manage` | 各线路通道占用/状态 |
| 进行中呼叫 | GET | `/api/call/active` | `sip:manage` | |

### 9.2 权限编码

| 权限编码 | 名称 | 默认角色 |
|----------|------|----------|
| `sip:manage` | 管理 SIP 线路 | PLATFORM_ADMIN, TENANT_ADMIN |

---

## 10. 错误码定义（sip 区间 11000-11999）

新增 `SipErrorCode` 枚举，通过 `new BizException(SipErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 11001 | SIP_LINE_NOT_FOUND | 线路不存在 | |
| 11002 | SIP_LINE_UNAVAILABLE | 线路不可用 | 无可用线路（全部 DEGRADED/DISABLED） |
| 11003 | SIP_LINE_CHANNEL_FULL | 线路通道已满 | 所有线路 usedChannels ≥ maxChannels |
| 11004 | SIP_INVITE_FAILED | SIP 呼叫发起失败 | 发送 INVITE 失败/超时 |
| 11005 | SIP_CALL_NOT_FOUND | SIP 呼叫不存在 | |
| 11006 | SIP_CALL_ENDED | SIP 呼叫已结束 | 操作已结束的呼叫 |
| 11007 | SIP_MEDIA_NEGOTIATION_FAILED | 媒体协商失败 | SDP 协商异常 |
| 11008 | SIP_RTP_ERROR | RTP 媒体流异常 | RTP 包处理错误 |
| 11009 | SIP_AUTH_FAILED | SIP 认证失败 | 线路 REGISTER 认证失败 |

---

## 11. 依赖与配置

### 11.1 Maven 依赖

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
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
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
- 引入 Netty：自建 SIP 协议栈与 RTP 媒体处理（SIP/RTP 是专用协议，无主流 Spring 生态的现成库，自建是必要的）
- 引入 Kafka：发布呼叫状态事件给 call-service
- 不引入 Dubbo：sip-connector 不参与 Dubbo 流式通信，媒体流通过内部接口推送给 call-service

### 11.2 application.yml 核心配置

```yaml
server:
  port: 8090

spring:
  application:
    name: vhuan-sip-connector
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:vhuan}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    producer:
      retries: 3
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

sip:
  # SIP 信令端口
  signal-port: 5060
  # RTP 媒体端口范围
  rtp-port-min: 10000
  rtp-port-max: 20000
  # 呼叫超时（秒）
  call-timeout-seconds: 30
  # 通话最大时长（分钟）
  max-call-duration-minutes: 30
  # 线路健康检查间隔（秒）
  health-check-interval-seconds: 30
  # 线路健康检查失败阈值（连续失败次数）
  health-fail-threshold: 3
```

---

## 12. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| SIP 协议栈 | 第三方库 vs 自建 Netty | **自建 Netty** | SIP/RTP 是专用协议，无主流 Spring 生态现成库；Netty 提供高性能 UDP 处理能力 |
| 线路管理 | 单线路 vs 多线路负载均衡 | **多线路加权负载均衡** | 运营商线路冗余，故障时自动切换，保障高可用 |
| 健康检查 | 被动感知 vs 主动探测 | **主动 OPTIONS 探测** | 定期探测线路可用性，故障前置发现 |
| 媒体流传输 | sip 直连 ai-engine vs 经 call-service | **经 call-service** | AGENTS.md 约束"ai-engine 不直接暴露"；sip-connector 只负责 RTP，不介入 AI 链路 |
| 呼叫状态通知 | HTTP 回调 vs Kafka 事件 | **Kafka 事件** | call-service 消费事件更新状态机，解耦且削峰 |
| 音频编解码 | 透传 vs 转 PCM | **转 PCM** | 统一为 PCM 16kHz 便于 ai-engine ASR 处理，屏蔽运营商编码差异 |
| RTP 抖动处理 | 无缓冲 vs JitterBuffer | **JitterBuffer** | 消除网络抖动，保证音频流畅 |

---

## 13. 自检清单

- [ ] SIP 协议栈：基于 Netty，处理 INVITE/RINGING/ANSWER/BYE/CANCEL/REGISTER/OPTIONS
- [ ] SIP 事务状态机：INVITING→RINGING→ANSWERED→IN_PROGRESS→ENDED 完整流转
- [ ] 呼叫控制 API：SipApi（invite/hangup/getCall/subscribeMedia/listLines）供 call-service 调用
- [ ] 线路管理：SipLine CRUD、加权负载均衡、通道占用控制
- [ ] 线路健康检查：OPTIONS 探测，3 次失败标记 DEGRADED，自动故障切换
- [ ] RTP 媒体流：JitterBuffer 抖动缓冲、RTP↔PCM 转换、媒体代理转发
- [ ] 反向媒体流：PCM → RTP 编码 → 打包 → 发送运营商
- [ ] 呼叫状态事件：Kafka 发布 sip.call.ringing/answered/failed/ended
- [ ] 错误码使用 `SipErrorCode`（11000-11999 区间）
- [ ] 数据表：sip_line 落在共享 Schema（平台级线路资源）
- [ ] 不引入 Dubbo，媒体流经 call-service 中转，ai-engine 不直接对接
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **第三阶段旁路支撑模块设计全部完成。**
>
> **总结**：`vhuan-contact`、`vhuan-analytics`、`vhuan-notification`、`vhuan-sip-connector` 四个模块详细设计完成。至此全系统 12 个模块（vhuan-common、vhuan-gateway、vhuan-auth、vhuan-tenant、vhuan-agent、vhuan-campaign、vhuan-call、vhuan-ai-engine、vhuan-contact、vhuan-analytics、vhuan-notification、vhuan-sip-connector）的详细设计均已完成。
