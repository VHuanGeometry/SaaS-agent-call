# vhuan-call 详细设计

> **模块**: vhuan-call（通话管理服务）  
> **阶段**: 第二阶段 — 核心业务链路  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

管理通话全生命周期：接收 campaign-service 下发的号码、发起 SIP 呼叫、管理媒体流、与 ai-engine 交互完成 AI 对话、坐席介入与切出、录音管理、并发通道控制。是核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）的第五环，也是系统最核心的执行层。

**职责边界**：
- 通话状态机：Idle → Dialing → Ringing → Answered → InProgress → Ended 六态流转
- 号码下发接收：Dubbo Triple CLIENT_STREAM 服务端，接收 campaign 推送的号码
- AI 引擎交互：Dubbo Triple BIDIRECTIONAL_STREAM，推送音频流、接收转写与 TTS 指令
- SIP 信令对接：通过 @HttpExchange 调用 sip-connector 发起/挂断呼叫
- 媒体流管理：音频流接收、转发给 ai-engine，TTS 音频回传播放
- 坐席介入：监听（WebSocket 订阅转写流）、切入（媒体流切换）、切出（恢复 AI）
- 录音管理：录音启停、混音、上传 MinIO
- 并发控制：调用 tenant-service 的 QuotaApi 预扣减/释放通道
- 通话事件发布：Kafka 发布通话生命周期事件

**非职责**：
- 不管理外呼任务调度（由 `vhuan-campaign` 负责），只接收下发的号码
- 不执行 ASR/NLU/DM/TTS（由 `vhuan-ai-engine` 负责），只负责音频流转发
- 不直接处理 SIP 协议栈（由 `vhuan-sip-connector` 负责），通过 @HttpExchange 调用
- 不管理 Agent 与话术配置（由 `vhuan-agent` 负责），通话开始时拉取快照传给 ai-engine

**模块拆分**：
- `vhuan-call-api`：Dubbo 接口（`CallDispatchApi`、`CallProcessApi`）与 DTO，供 campaign-service 和 ai-engine-service 通过 Maven 依赖引用
- `vhuan-call`：实现模块，包含状态机、SIP 对接、媒体流、录音等完整逻辑

---

## 2. 模块结构

```
vhuan-call-api/                          # 接口与 DTO 模块（供调用方引用）
├── pom.xml
└── src/main/java/com/vhuan/call/api/
    ├── CallDispatchApi.java              # 号码下发接口（CLIENT_STREAM，campaign 调用）
    ├── CallProcessApi.java               # 通话处理接口（BIDI_STREAM，ai-engine 调用）
    └── dto/
        ├── CallDispatchRequest.java       # 号码下发请求 DTO
        ├── CallDispatchResponse.java      # 号码下发汇总响应 DTO
        ├── CallRequest.java               # 通话请求（音频流 + 控制指令）
        └── CallResponse.java              # 通话响应（转写 + TTS 指令）

vhuan-call/                               # 实现模块
├── pom.xml
├── src/main/java/com/vhuan/call/
│   ├── CallApplication.java               # 启动类
│   │
│   ├── controller/
│   │   ├── CallController.java            # 通话管理 API（查询/挂断/转人工）
│   │   ├── CallMonitorController.java     # 通话监控 API（实时列表/监听）
│   │   └── CallRecordingController.java   # 录音管理 API
│   │
│   ├── service/
│   │   ├── CallSessionService.java        # 通话会话管理
│   │   ├── CallStateMachine.java          # 通话状态机
│   │   ├── CallDispatchService.java       # 号码下发处理（Dubbo 服务端）
│   │   ├── CallProcessService.java        # AI 通话处理（Dubbo BIDI 服务端）
│   │   ├── SipCallService.java            # SIP 呼叫对接
│   │   ├── MediaStreamService.java        # 媒体流管理
│   │   ├── RecordingService.java          # 录音管理
│   │   ├── AgentInterceptService.java     # 坐席介入管理
│   │   ├── CallEventPublisher.java        # Kafka 事件发布
│   │   └── impl/
│   │       ├── CallSessionServiceImpl.java
│   │       ├── CallDispatchServiceImpl.java
│   │       ├── CallProcessServiceImpl.java
│   │       ├── SipCallServiceImpl.java
│   │       ├── MediaStreamServiceImpl.java
│   │       ├── RecordingServiceImpl.java
│   │       ├── AgentInterceptServiceImpl.java
│   │       └── CallEventPublisherImpl.java
│   │
│   ├── mapper/
│   │   ├── CallSessionMapper.java
│   │   ├── CallRecordingMapper.java
│   │   ├── CallTranscriptMapper.java
│   │   ├── CallIntentResultMapper.java
│   │   └── CallSlotMapper.java
│   │
│   ├── entity/
│   │   ├── CallSession.java
│   │   ├── CallRecording.java
│   │   ├── CallTranscript.java
│   │   ├── CallIntentResult.java
│   │   └── CallSlot.java
│   │
│   ├── dto/
│   │   ├── CallStartRequest.java          # 单次呼叫请求（预览式外呼）
│   │   ├── CallQueryRequest.java
│   │   └── InterceptRequest.java          # 坐席介入请求
│   │
│   ├── vo/
│   │   ├── CallSessionVO.java
│   │   ├── CallRecordingVO.java
│   │   ├── CallMonitorVO.java            # 监控面板数据
│   │   └── CallTranscriptVO.java
│   │
│   ├── remote/
│   │   ├── SipConnectorClient.java        # @HttpExchange 调用 sip-connector
│   │   ├── AgentClient.java               # @HttpExchange 调用 agent-service
│   │   ├── QuotaClient.java              # @HttpExchange 调用 tenant-service
│   │   └── dto/
│   │       └── SipInviteRequest.java       # SIP 呼叫请求
│   │
│   ├── websocket/
│   │   └── CallMonitorHandler.java        # WebSocket 推送（netty-socketio）
│   │
│   ├── enums/
│   │   ├── CallStatus.java                # 通话状态枚举
│   │   ├── CallDirection.java             # 呼叫方向
│   │   ├── CallResult.java               # 呼叫结果
│   │   ├── InterceptType.java             # 介入类型
│   │   └── CallEventType.java             # Kafka 事件类型
│   │
│   └── config/
│       ├── CallProperties.java            # 服务配置
│       ├── DubboConfig.java               # Dubbo 服务端配置
│       └── WebSocketConfig.java           # netty-socketio 配置
│
└── src/main/resources/
    └── application.yml
```

---

## 3. Dubbo 接口契约（vhuan-call-api）

根据 AGENTS.md 约束："Dubbo 服务接口与 DTO 定义在被调用方服务模块内，由调用方通过 Maven 依赖引用"。

### 3.1 CallDispatchApi — 号码下发（CLIENT_STREAM）

```java
/**
 * 号码下发接口 — Dubbo Triple CLIENT_STREAM
 * campaign-service 作为客户端流式推送号码，call-service 作为服务端接收并处理
 */
public interface CallDispatchApi {

    /**
     * 批量下发号码
     *
     * CLIENT_STREAM 模式：
     * - campaign（客户端）通过返回的 StreamObserver 逐条推送 CallDispatchRequest
     * - call-service（服务端）接收每条号码，预扣减通道后发起 SIP 呼叫
     * - 所有号码推送完成后，返回一个汇总响应 CallDispatchResponse
     *
     * @param responseObserver 响应观察者（接收汇总结果）
     * @return 请求观察者（campaign 用于推送号码）
     */
    StreamObserver<CallDispatchRequest> dispatchNumbers(
        StreamObserver<CallDispatchResponse> responseObserver
    );
}
```

### 3.2 CallProcessApi — AI 通话处理（BIDIRECTIONAL_STREAM）

```java
/**
 * AI 通话处理接口 — Dubbo Triple BIDIRECTIONAL_STREAM
 * call-service 推送音频流，ai-engine 返回转写 + TTS 指令
 */
public interface CallProcessApi {

    /**
     * 处理通话
     *
     * BIDI STREAM 模式：
     * - call-service（客户端）通过返回的 StreamObserver 推送音频流和控制指令
     * - ai-engine（服务端）逐条返回转写结果、意图、TTS 音频指令
     * - 双向持续到通话结束
     *
     * @param responseObserver 响应观察者（接收 ai-engine 的转写 + TTS 指令）
     * @return 请求观察者（call-service 用于推送音频流）
     */
    StreamObserver<CallRequest> processCall(
        StreamObserver<CallResponse> responseObserver
    );
}
```

### 3.3 DTO 定义

```java
/**
 * 号码下发请求 — campaign 推送给 call-service
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
 * 批次下发汇总响应
 */
public record CallDispatchResponse(
    int totalCount,          // 接收号码总数
    int acceptedCount,       // 接受下发数（通道充足）
    int rejectedCount,       // 拒绝下发数（通道不足/租户暂停等）
    String summary           // 汇总信息
) {}

/**
 * 通话请求 — call-service 推送给 ai-engine
 * 包含音频流和控制指令两种类型
 */
public record CallRequest(
    String sessionId,        // 通话会话 ID
    RequestType type,        // 请求类型：AUDIO（音频流）/ CONTROL（控制指令）/ INIT（初始化）
    byte[] audioData,        // 音频数据（PCM 16kHz 16bit mono，type=AUDIO 时有效）
    String controlCommand,  // 控制指令（type=CONTROL 时有效，如 START/STOP/INTERRUPT）
    String scriptSnapshot   // 话术配置快照 JSON（type=INIT 时传入）
) {}

/**
 * 通话响应 — ai-engine 返回给 call-service
 */
public record CallResponse(
    String sessionId,        // 通话会话 ID
    ResponseType type,       // 响应类型：ASR（转写）/ NLU（意图）/ TTS（语音合成）/ DM（状态变更）/ SLOT（槽位）
    String asrText,          // ASR 转写文本（type=ASR 时有效）
    String intent,           // 意图分类结果（type=NLU 时有效）
    byte[] ttsAudio,         // TTS 合成音频（type=TTS 时有效）
    String currentNodeId,    // 当前话术节点 ID（type=DM 时有效）
    String slotKey,          // 槽位 key（type=SLOT 时有效）
    String slotValue         // 槽位 value（type=SLOT 时有效）
) {}
```

**请求类型枚举**：

```java
public enum RequestType {
    INIT,      // 初始化（传递话术快照、Agent 配置）
    AUDIO,     // 音频流（PCM 数据）
    CONTROL    // 控制指令（开始/停止/打断）
}

public enum ResponseType {
    ASR,       // ASR 转写结果
    NLU,       // NLU 意图分类
    TTS,       // TTS 语音合成
    DM,        // 对话管理状态变更
    SLOT       // 槽位收集结果
}
```

---

## 4. 数据模型设计

所有表落在**租户专属 Schema**（`tenant_{tenantCode}`）。

### 4.1 表关系

```
┌──────────────────┐     ┌──────────────────┐
│ call_session       │     │ call_recording    │
│──────────────────│     │──────────────────│
│ id                 │     │ id                │
│ session_code       │     │ session_id         │
│ campaign_id        │     │ file_url           │
│ detail_id          │     │ file_size         │
│ phone              │     │ duration          │
│ direction          │     │ format            │
│ status             │     └──────────────────┘
│ agent_code         │
│ sip_call_id        │     ┌──────────────────┐
│ channel_credential │     │ call_transcript   │
│ start_time         │     │──────────────────│
│ answer_time        │     │ id                │
│ end_time           │     │ session_id         │
│ duration           │     │ sequence          │
│ call_result        │     │ role              │
│ intent_tag         │     │ text              │
│ intercept_type     │     │ audio_start_ms    │
│ intercept_agent_id │     │ audio_end_ms      │
└──────────────────┘     └──────────────────┘

                          ┌──────────────────────┐
                          │ call_intent_result    │
                          │──────────────────────│
                          │ id                    │
                          │ session_id            │
                          │ node_id               │
                          │ intent                │
                          │ confidence            │
                          │ timestamp             │
                          └──────────────────────┘

                          ┌──────────────────┐
                          │ call_slot          │
                          │──────────────────│
                          │ id                │
                          │ session_id        │
                          │ slot_key          │
                          │ slot_value        │
                          │ collected_at      │
                          └──────────────────┘
```

### 4.2 CallSession

```java
@TableName("call_session")
public class CallSession extends BaseEntity {

    /** 通话会话编码（唯一，对前端展示） */
    @Column
    private String sessionCode;

    /** 所属任务 ID（引用 campaign） */
    @Column
    private String campaignId;

    /** 任务明细 ID（引用 campaign_detail） */
    @Column
    private String detailId;

    /** 被叫号码 */
    @Column
    private String phone;

    /** 客户姓名 */
    @Column
    private String customerName;

    /** 呼叫方向：OUTBOUND=外呼，INBOUND=呼入 */
    @Column
    private String direction;

    /** 通话状态（见 CallStatus 枚举） */
    @Column
    private String status;

    /** Agent 编码 */
    @Column
    private String agentCode;

    /** SIP 呼叫 ID（sip-connector 返回） */
    @Column
    private String sipCallId;

    /** 通道预扣减凭据 ID（调用 QuotaApi 时获取） */
    @Column
    private String channelCredential;

    /** 通话开始时间（发起 SIP 呼叫） */
    @Column
    private LocalDateTime startTime;

    /** 接听时间（SIP 200 OK） */
    @Column
    private LocalDateTime answerTime;

    /** 通话结束时间 */
    @Column
    private LocalDateTime endTime;

    /** 通话时长（秒） */
    @Column
    private Integer duration;

    /** 呼叫结果（见 CallResult 枚举） */
    @Column
    private String callResult;

    /** 意向标签（A/B/C/D 类） */
    @Column
    private String intentTag;

    /** 介入类型：NONE=无介入，LISTEN=监听，INTERCEPT=切入 */
    @Column
    private String interceptType;

    /** 介入坐席 ID */
    @Column
    private String interceptAgentId;

    /** 介入时间 */
    @Column
    private LocalDateTime interceptTime;
}
```

### 4.3 CallRecording

```java
@TableName("call_recording")
public class CallRecording extends BaseEntity {

    /** 通话会话 ID */
    @Column
    private String sessionId;

    /** 录音文件 URL（MinIO 路径） */
    @Column
    private String fileUrl;

    /** 文件大小（字节） */
    @Column
    private Long fileSize;

    /** 录音时长（秒） */
    @Column
    private Integer duration;

    /** 音频格式：WAV / MP3 */
    @Column
    private String format;
}
```

### 4.4 CallTranscript

```java
@TableName("call_transcript")
public class CallTranscript extends BaseEntity {

    /** 通话会话 ID */
    @Column
    private String sessionId;

    /** 对话序号（从 1 开始递增） */
    @Column
    private Integer sequence;

    /** 角色：AI=AI 语音，CUSTOMER=客户语音，AGENT=坐席语音 */
    @Column
    private String role;

    /** 转写文本 */
    @Column
    private String text;

    /** 音频起始时间戳（毫秒，相对于通话开始） */
    @Column
    private Long audioStartMs;

    /** 音频结束时间戳（毫秒） */
    @Column
    private Long audioEndMs;
}
```

### 4.5 CallIntentResult / CallSlot

```java
@TableName("call_intent_result")
public class CallIntentResult extends BaseEntity {
    /** 通话会话 ID */
    @Column
    private String sessionId;
    /** 话术节点 ID */
    @Column
    private String nodeId;
    /** 意图分类结果 */
    @Column
    private String intent;
    /** 置信度（0-1） */
    @Column
    private Double confidence;
    /** 时间戳 */
    @Column
    private LocalDateTime timestamp;
}

@TableName("call_slot")
public class CallSlot extends BaseEntity {
    /** 通话会话 ID */
    @Column
    private String sessionId;
    /** 槽位 key */
    @Column
    private String slotKey;
    /** 槽位值 */
    @Column
    private String slotValue;
    /** 收集时间 */
    @Column
    private LocalDateTime collectedAt;
}
```

---

## 5. 通话状态机

### 5.1 状态定义

```java
public enum CallStatus {
    IDLE,        // 空闲（创建会话，尚未发起呼叫）
    DIALING,     // 拨号中（已发送 SIP Invite）
    RINGING,     // 振铃中（收到 SIP 180 Ringing）
    ANSWERED,    // 已接通（收到 SIP 200 OK）
    IN_PROGRESS, // 通话中（AI 引擎接管，正在进行对话）
    ENDING,      // 结束中（挂断 SIP，清理资源）
    ENDED        // 已结束（资源清理完毕，写入最终状态）
}
```

### 5.2 状态流转

```
            创建会话
               │
               ▼
          ┌─────────┐
          │  IDLE    │
          └────┬────┘
               │ 发起 SIP Invite
               │ QuotaApi.acquireChannel()
               │ SipConnectorClient.invite()
          ┌────▼────┐
          │ DIALING  │
          └────┬────┘
               │ 收到 SIP 180 Ringing
          ┌────▼────┐
          │ RINGING  │
          └────┬────┘
               │
        ┌──────┼──────────────┐
        │      │              │
   接通 │      │ 未接通        │ 超时
        │      │ BUSY/REJECTED │ NO_ANSWER
        │      │ FAILED        │ TIMEOUT
        ▼      ▼              ▼
   ┌─────────┐  ┌──────────┐  ┌──────────┐
   │ANSWERED │  │  ENDING  │  │  ENDING  │
   └────┬────┘  └────┬─────┘  └────┬─────┘
        │            │             │
        │ AI 接管     │             │
        │ Dubbo BIDI  │             │
        │ 开始通话     │             │
   ┌────▼────┐       │             │
   │IN_PROGRESS│      │             │
   └────┬────┘       │             │
        │ 通话结束     │             │
        │ 挂断 SIP    │             │
        │ 释放通道     │             │
   ┌────▼────┐       │             │
   │ ENDING  │       │             │
   └────┬────┘       │             │
        │ 资源清理    │             │
        │ 录音上传    │             │
        │ Kafka 事件  │             │
   ┌────▼────┐       │             │
   │ ENDED   │◀──────┴─────────────┘
   └─────────┘
```

### 5.3 状态转换规则

| 当前状态 | 事件 | 目标状态 | 动作 |
|----------|------|----------|------|
| IDLE | 发起呼叫 | DIALING | 预扣减通道、SIP Invite、Kafka `call.created` |
| DIALING | 收到 180 Ringing | RINGING | — |
| DIALING | 收到 200 OK | ANSWERED | Kafka `call.answered` |
| DIALING | 收到 4xx/5xx/超时 | ENDING | 记录呼叫结果 |
| RINGING | 收到 200 OK | ANSWERED | Kafka `call.answered` |
| RINGING | 超时(30s) | ENDING | 呼叫结果=NO_ANSWER |
| ANSWERED | AI 引擎接管 | IN_PROGRESS | 发起 Dubbo BIDI 流、启动录音 |
| IN_PROGRESS | 通话结束 | ENDING | 停止录音、挂断 SIP、释放通道 |
| IN_PROGRESS | 坐席切入 | IN_PROGRESS | 媒体流切换、AI 静音 |
| IN_PROGRESS | 坐席切出 | IN_PROGRESS | 恢复 AI 接管 |
| ENDING | 资源清理完毕 | ENDED | Kafka `call.ended`、写入最终状态 |

---

## 6. 号码下发处理（CLIENT_STREAM 服务端）

### 6.1 处理逻辑

```java
/**
 * Dubbo CLIENT_STREAM 服务端实现
 * campaign-service 推送号码，call-service 逐条接收处理
 */
@DubboService
public class CallDispatchServiceImpl implements CallDispatchApi {

    @Override
    public StreamObserver<CallDispatchRequest> dispatchNumbers(
            StreamObserver<CallDispatchResponse> responseObserver) {

        // 返回请求观察者，campaign 通过它逐条推送号码
        return new StreamObserver<>() {
            final AtomicInteger totalCount = new AtomicInteger(0);
            final AtomicInteger acceptedCount = new AtomicInteger(0);
            final AtomicInteger rejectedCount = new AtomicInteger(0);

            @Override
            public void onNext(CallDispatchRequest request) {
                totalCount.incrementAndGet();

                // 1. 校验租户通道配额
                boolean channelAvailable = quotaClient.checkChannels(tenantId);
                if (!channelAvailable) {
                    rejectedCount.incrementAndGet();
                    return;
                }

                // 2. 预扣减通道
                String credential = quotaClient.acquireChannel(tenantId);

                // 3. 创建通话会话
                CallSession session = createCallSession(request, credential);

                // 4. 异步发起 SIP 呼叫（虚拟线程）
                Thread.startVirtualThread(() -> {
                    initiateCall(session, request);
                });

                acceptedCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {
                log.error("号码下发流异常", t);
            }

            @Override
            public void onCompleted() {
                // campaign 推送完毕，返回汇总
                responseObserver.onNext(new CallDispatchResponse(
                    totalCount.get(),
                    acceptedCount.get(),
                    rejectedCount.get(),
                    "批次下发完成"
                ));
                responseObserver.onCompleted();
            }
        };
    }
}
```

**设计要点**：
- 每个号码的 SIP 呼叫在独立虚拟线程中发起，不阻塞 CLIENT_STREAM 的接收
- 通道预扣减在接收时同步执行，确保不超租户配额
- SIP 呼叫异步执行，通过 Kafka 事件回传结果给 campaign-service

### 6.2 单个号码的呼叫流程

```
收到 CallDispatchRequest
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 预扣减通道配额                      │
│    QuotaApi.acquireChannel(tenantId)   │
│    获取 credential                     │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 创建 CallSession                    │
│    status=IDLE                        │
│    保存 channelCredential              │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 拉取 Agent 配置快照                 │
│    AgentApi.getAgentSnapshot(agentCode)│
│    获取话术树、音色、LLM 配置           │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 发起 SIP Invite                     │
│    SipConnectorClient.invite(phone)   │
│    status=DIALING                     │
│    发布 Kafka: call.created            │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 5. 等待 SIP 响应                       │
│    180 Ringing → status=RINGING       │
│    200 OK → status=ANSWERED           │
│    4xx/5xx/超时 → status=ENDING       │
└────────────┬─────────────────────────┘
             ▼
           接通？
        ┌──┴──┐
       是     否
        │      │
        ▼      ▼
┌──────────┐  ┌──────────────────┐
│ AI 接管  │  │ 记录呼叫结果       │
│ (见第7节) │  │ 释放通道配额        │
└──────────┘  │ status=ENDED      │
              │ 发布 Kafka: call.ended│
              └──────────────────┘
```

---

## 7. AI 通话处理（BIDIRECTIONAL_STREAM）

### 7.1 双向流交互时序

```
call-service                        ai-engine-service
    │                                      │
    │ 通话接通，AI 接管                      │
    │ 1. 建立 Dubbo BIDI 流                  │
    │──processCall(responseObserver)───────▶│
    │◀────requestObserver──────────────────│
    │                                      │
    │ 2. 推送初始化指令                      │
    │──onNext(INIT + scriptSnapshot)──────▶│
    │                                      │
    │ 3. 启动录音                           │
    │                                      │
    │ ┌─ 循环：音频流交互 ──────────────┐    │
    │ │                                │    │
    │ │ 4. 推送客户音频                  │    │
    │ │──onNext(AUDIO + pcm)──────────▶│    │
    │ │                                │    │
    │ │ 5. 接收 ASR 转写               │    │
    │ │◀──onNext(ASR + text)──────────│    │
    │ │   写入 call_transcript         │    │
    │ │   WebSocket 推送转写到监控面板   │    │
    │ │                                │    │
    │ │ 6. 接收 NLU 意图               │    │
    │ │◀──onNext(NLU + intent)────────│    │
    │ │   写入 call_intent_result      │    │
    │ │                                │    │
    │ │ 7. 接收 DM 状态变更              │    │
    │ │◀──onNext(DM + currentNodeId)──│    │
    │ │   发布 Kafka: call.dm.state_changed│
    │ │                                │    │
    │ │ 8. 接收 TTS 音频               │    │
    │ │◀──onNext(TTS + audio)─────────│    │
    │ │   播放 TTS 音频给客户           │    │
    │ │   写入 call_transcript(AI角色)  │    │
    │ │   发布 Kafka: call.tts.playing  │    │
    │ │                                │    │
    │ │ 9. 接收槽位收集                 │    │
    │ │◀──onNext(SLOT + key/value)────│    │
    │ │   写入 call_slot               │    │
    │ │   发布 Kafka: call.slot.collected│
    │ │                                │    │
    │ └────────────────────────────────┘    │
    │                                      │
    │ 10. 通话结束                         │
    │──onNext(CONTROL + STOP)──────────────▶│
    │──onCompleted()───────────────────────▶│
    │                                      │
    │ 11. 停止录音，上传 MinIO               │
    │ 12. 释放通道配额                       │
    │ 13. 发布 Kafka: call.ended            │
```

### 7.2 媒体流管理

```
                    ┌──────────────┐
                    │  SIP/RTP     │
                    │  (运营商)     │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │sip-connector │
                    └──────┬───────┘
                           │ RTP 音频流
                    ┌──────▼───────┐
                    │ call-service │
                    │  MediaStream │
                    │  Manager     │
                    └──┬───┬───┬──┘
            ┌──────────┘   │   └──────────┐
            │              │              │
   ┌────────▼──┐  ┌────────▼───┐  ┌──────▼──────┐
   │ Dubbo BIDI│  │  录音模块   │  │ WebSocket   │
   │ →ai-engine│  │  (混音)    │  │ →监控面板   │
   │ 推送音频   │  │  本地写入   │  │ 推送转写流   │
   └───────────┘  └────────────┘  └─────────────┘
```

**音频流方向**：
- **客户 → AI**：SIP/RTP → sip-connector → call-service → Dubbo BIDI 推送 PCM → ai-engine ASR
- **AI → 客户**：ai-engine TTS → Dubbo BIDI 返回 → call-service → sip-connector → SIP/RTP → 客户
- **录音**：call-service 将双向音频混音后写入本地临时文件，通话结束后上传 MinIO

### 7.3 虚拟线程绑定

```java
/**
 * 每个通话在独立虚拟线程中执行
 * Scoped Values 传递租户上下文（非 ThreadLocal）
 */
public class CallProcessServiceImpl implements CallProcessApi {

    @Override
    public StreamObserver<CallRequest> processCall(
            StreamObserver<CallResponse> responseObserver) {

        return new StreamObserver<>() {
            private String sessionId;
            private CallSession session;
            private ScriptSnapshot scriptSnapshot;

            @Override
            public void onNext(CallRequest request) {
                // 在虚拟线程中处理每个请求
                Thread.startVirtualThread(() -> {
                    // ScopedValue 传递租户上下文
                    ScopedValue.where(TenantContextHolder.getScopedValue(), 
                        buildContext(session)).run(() -> {
                        processRequest(request, responseObserver);
                    });
                });
            }

            private void processRequest(CallRequest request, 
                    StreamObserver<CallResponse> responseObserver) {
                switch (request.type()) {
                    case INIT -> handleInit(request);
                    case AUDIO -> handleAudio(request, responseObserver);
                    case CONTROL -> handleControl(request, responseObserver);
                }
            }
        };
    }
}
```

**设计决策**：每个 `onNext` 回调在独立虚拟线程中处理，避免阻塞 Dubbo 的 I/O 线程。音频处理（写入录音、推送 WebSocket）是 I/O 密集型操作，虚拟线程在阻塞时自动让出载体线程。租户上下文通过 Scoped Values 传递（非 ThreadLocal），与 AGENTS.md 约束一致。

---

## 8. 坐席介入

### 8.1 三种介入模式

| 模式 | 接口 | 说明 | 媒体流变化 |
|------|------|------|-----------|
| 监听 (LISTEN) | WebSocket 订阅 | 坐席只看转写流，不影响通话 | 无变化，AI 继续通话 |
| 切入 (INTERCEPT) | @HttpExchange 调用 | 坐席接管通话，AI 静音 | 坐席音频 → 客户，客户音频 → 坐席 + AI(静音) |
| 切出 (RELEASE) | @HttpExchange 调用 | 坐席退出，AI 恢复接管 | 恢复 AI 音频 → 客户 |

### 8.2 切入流程

```
坐席在监控面板点击"切入"
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 更新 CallSession                  │
│    interceptType=INTERCEPT           │
│    interceptAgentId=当前坐席          │
│    interceptTime=now()               │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 通知 ai-engine 暂停                │
│    Dubbo CONTROL: INTERRUPT          │
│    ai-engine 停止 TTS 输出            │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 切换媒体流                         │
│    sip-connector: 坐席媒体通道接入      │
│    客户音频 → 坐席 + AI(静音)          │
│    坐席音频 → 客户                    │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. WebSocket 通知监控面板             │
│    通话状态变为"坐席接管"               │
└──────────────────────────────────────┘
```

### 8.3 切出流程

```
坐席点击"切出"或挂断
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 切换媒体流回 AI                     │
│    sip-connector: 坐席媒体通道断开      │
│    恢复 AI 音频 → 客户                │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 通知 ai-engine 恢复                │
│    Dubbo CONTROL: RESUME              │
│    ai-engine 从当前节点继续对话         │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 更新 CallSession                   │
│    interceptType=NONE                 │
└──────────────────────────────────────┘
```

---

## 9. Kafka 事件设计

### 9.1 发布的通话生命周期事件

```
call.created          → 通话会话创建（发起 SIP 呼叫）
call.answered         → 客户接听（SIP 200 OK）
call.asr.partial       → ASR 实时转写（通话过程中持续发布）
call.nlu.intent       → NLU 意图识别结果
call.dm.state_changed  → 话术状态机节点变更
call.tts.playing       → TTS 开始播放
call.slot.collected   → 槽位收集完成
call.ended            → 通话结束（含呼叫结果、时长、意向标签）
call.intent_tagged    → 意向打标完成
call.recording.ready  → 录音文件准备完毕（上传 MinIO 完成）
```

### 9.2 事件结构

```java
/**
 * 通话结束事件 — campaign-service 消费后更新任务进度
 */
public record CallEndedEvent(
    String tenantId,       // 租户 ID
    String sessionId,      // 通话会话 ID
    String detailId,       // campaign_detail ID
    String campaignId,     // 任务 ID
    String callResult,     // 呼叫结果（ANSWERED/NO_ANSWER/BUSY/REJECTED/FAILED）
    Integer duration,      // 通话时长（秒）
    String intentTag,      // 意向标签（A/B/C/D）
    LocalDateTime endTime  // 结束时间
) {}

/**
 * 录音就绪事件 — tenant-service 消费后累计存储用量
 */
public record CallRecordingReadyEvent(
    String tenantId,
    String sessionId,
    String fileUrl,        // MinIO URL
    Long fileSize,         // 文件大小（字节）
    Integer duration       // 录音时长（秒）
) {}
```

### 9.3 事件消费方

| 事件 | campaign-service | tenant-service | analytics-service | notification-service |
|------|:-:|:-:|:-:|:-:|
| call.created | | | ✓ | |
| call.answered | | | ✓ | |
| call.ended | ✓ | ✓ | ✓ | ✓ |
| call.recording.ready | | ✓ | | |
| call.intent_tagged | ✓ | | ✓ | |

---

## 10. 对外接口设计

### 10.1 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 通话列表 | GET | `/api/call/list` | `call:view` | 分页查询历史通话 |
| 通话详情 | GET | `/api/call/{id}` | `call:view` | 含转写、意向、槽位 |
| 挂断通话 | POST | `/api/call/{id}/hangup` | `call:intercept` | 强制挂断 |
| 转人工 | POST | `/api/call/{id}/transfer-human` | `call:transfer` | 转接坐席 |
| 实时通话列表 | GET | `/api/call/monitor/active` | `call:view` | 当前进行中的通话 |
| 监听通话 | WS | `/ws/call/{id}/listen` | `call:view` | WebSocket 订阅转写流 |
| 坐席切入 | POST | `/api/call/{id}/intercept` | `call:intercept` | 切入接管 |
| 坐席切出 | POST | `/api/call/{id}/release` | `call:intercept` | 恢复 AI |
| 录音列表 | GET | `/api/recording/list` | `call:view` | 按会话查询录音 |
| 下载录音 | GET | `/api/recording/{id}/download` | `call:view` | 返回 MinIO 下载 URL |
| 转写记录 | GET | `/api/call/{id}/transcript` | `call:view` | 完整对话转写 |

### 10.2 预览式外呼接口

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 单次呼叫 | POST | `/api/internal/call/single` | 内部调用 | campaign 预览式外呼调用 |

```java
/**
 * 预览式外呼 — 单次呼叫
 * campaign-service 通过 @HttpExchange 调用
 */
@HttpExchange(url = "${service.call.url}", name = "callInternalApi")
public interface CallInternalApi {

    @PostExchange("/api/internal/call/single")
    CallSessionVO singleCall(@RequestBody CallStartRequest request);
}
```

### 10.3 权限编码

| 权限编码 | 名称 | 默认角色 |
|----------|------|----------|
| `call:view` | 查看通话 | TENANT_ADMIN, SUPERVISOR, AGENT |
| `call:intercept` | 介入通话 | TENANT_ADMIN, SUPERVISOR |
| `call:transfer` | 转人工 | TENANT_ADMIN, SUPERVISOR |

---

## 11. 错误码定义（call 区间 6000-6999）

新增 `CallErrorCode` 枚举，通过 `new BizException(CallErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 6001 | CALL_SESSION_NOT_FOUND | 通话会话不存在 | |
| 6002 | CALL_NOT_IN_PROGRESS | 通话不在进行中 | 介入非 IN_PROGRESS 状态的通话 |
| 6003 | CALL_ALREADY_INTERCEPTED | 通话已被介入 | 重复切入 |
| 6004 | CALL_RECORDING_FAILED | 录音失败 | 录音文件写入/上传异常 |
| 6005 | SIP_INVITE_FAILED | SIP 呼叫失败 | sip-connector 返回错误 |
| 6006 | SIP_HANGUP_FAILED | SIP 挂断失败 | |
| 6007 | MEDIA_STREAM_ERROR | 媒体流异常 | RTP 处理错误 |
| 6008 | AI_ENGINE_UNAVAILABLE | AI 引擎不可用 | Dubbo BIDI 流建立失败 |
| 6009 | QUOTA_ACQUIRE_FAILED | 通道配额获取失败 | QuotaApi.acquireChannel 失败 |
| 6010 | AGENT_SNAPSHOT_FAILED | 获取 Agent 配置失败 | AgentApi 调用异常 |
| 6011 | CALL_ALREADY_ENDED | 通话已结束 | 操作已 ENDED 的通话 |
| 6012 | INTERCEPT_CONFLICT | 介入冲突 | 已有坐席介入，拒绝第二个 |
| 6013 | RECORDING_NOT_FOUND | 录音文件不存在 | |
| 6014 | CALL_TIMEOUT | 通话超时 | 通话超过最大时长限制 |

---

## 12. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `call:active:{tenantId}` | Set(sessionId) | 无 | 当前租户活跃通话会话集合（实时监控） |
| `call:session:{sessionId}` | Hash | 通话结束后 1h | 通话实时状态缓存（status/agentCode/phone/startTime） |
| `call:transcript:{sessionId}` | List | 通话结束后 1h | 实时转写缓冲（WebSocket 推送前先写 Redis） |
| `call:intercept:{sessionId}` | String(agentId) | 通话期间 | 当前介入坐席 ID（防并发介入） |

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

    <!-- Dubbo（Triple 协议，CLIENT_STREAM 号码下发 + BIDI STREAM 音频流） -->
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

    <!-- Sentinel（@HttpExchange 熔断降级） -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    </dependency>

    <!-- netty-socketio（WebSocket 实时推送） -->
    <dependency>
        <groupId>io.github.dreamroute</groupId>
        <artifactId>netty-socketio-spring-boot-starter</artifactId>
    </dependency>

    <!-- MinIO（录音文件存储） -->
    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
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
- 引入 Dubbo：作为 CLIENT_STREAM 服务端（接收号码下发）和 BIDI STREAM 客户端（推送音频到 ai-engine）
- 引入 netty-socketio：监控面板实时推送转写流和通话状态
- 引入 MinIO：录音文件上传存储
- 不依赖 `vhuan-ai-engine` 模块实现，通过 Dubbo `@DubboReference` 引用 `CallProcessApi` 接口
- 不依赖 `vhuan-agent` 模块，通过 @HttpExchange 调用 AgentApi

### 13.2 application.yml 核心配置

```yaml
server:
  port: 8085

spring:
  application:
    name: vhuan-call
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
    producer:
      retries: 3
    consumer:
      group-id: vhuan-call
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
    name: vhuan-call
  protocol:
    name: tri
    port: 20885  # Dubbo 端口与 HTTP 端口分离
  registry:
    address: nacos://${NACOS_ADDR:localhost:8848}?namespace=${NACOS_NAMESPACE:vhuan}
  provider:
    timeout: 60000  # BIDI STREAM 长连接超时 60s
  consumer:
    timeout: 60000
    retries: 0  # 流式调用不重试

# netty-socketio 配置
socketio:
  host: 0.0.0.0
  port: 9092
  boss-threads: 1
  worker-threads: 8

# MinIO 配置
minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket: ${MINIO_BUCKET:vhuan-recordings}

mybatis-flex:
  mapper-locations: classpath*:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

# 通话服务配置
call:
  # SIP 呼叫超时（秒）— 振铃无应答超时
  sip-timeout-seconds: 30
  # 通话最大时长（分钟）— 超过自动挂断
  max-call-duration-minutes: 30
  # 录音临时文件路径
  recording-temp-path: /tmp/vhuan-recordings
  # 音频格式
  audio-format: WAV
  # 音频采样率
  audio-sample-rate: 16000

# 远程服务地址
service:
  sip:
    url: http://vhuan-sip-connector
  agent:
    url: http://vhuan-agent
  tenant:
    url: http://vhuan-tenant
```

---

## 14. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 模块拆分 | 单模块 vs api + 实现拆分 | **拆分 vhuan-call-api** | Dubbo 接口与 DTO 定义在被调用方，campaign/ai-engine 只需引用 api 包 |
| 号码下发处理 | 同步处理 vs 异步虚拟线程 | **异步虚拟线程** | SIP 呼叫是 I/O 密集操作，虚拟线程不阻塞 Dubbo I/O 线程 |
| AI 音频流传输 | WebSocket vs Dubbo BIDI STREAM | **Dubbo BIDI STREAM** | AGENTS.md 约束，基于 HTTP/2 全双工流，天然支持双向音频流 |
| 转写流推送前端 | ai-engine 直接推 vs call-service 中转 | **call-service 中转** | AGENTS.md 约束"ai-engine 不直接暴露给前端"，call-service 通过 WebSocket 推送 |
| SIP 协议栈 | 内嵌 vs 独立 sip-connector | **独立 sip-connector** | SIP 协议栈复杂度高，独立模块便于运维和运营商线路管理 |
| 通话状态机 | 简化 3 态 vs 完整 6 态 | **完整 6 态** | 区分 DIALING/RINGING/ANSWERED 便于监控面板精确展示呼叫进度 |
| 录音存储 | 实时上传 vs 通话后上传 | **通话后上传** | 实时上传网络开销大；通话结束后一次性混音上传，降低 I/O 压力 |
| 坐席介入通信 | Dubbo vs @HttpExchange | **@HttpExchange** | 介入是低频管理操作，HTTP 足够；Dubbo 专用于流式场景 |
| 音频流处理线程 | 线程池 vs 虚拟线程 | **虚拟线程** | 每个 onNext 在虚拟线程中处理，I/O 阻塞时自动让出载体线程 |
| 通话结果回传 | Dubbo 响应流 vs Kafka 事件 | **Kafka 事件** | 通话结束是异步事件，Kafka 解耦削峰；Dubbo 流用于音频交互方向 |
| 通话超时处理 | 不限制 vs 自动挂断 | **30 分钟自动挂断** | 防止异常通话长时间占用通道 |
| 预览式外呼 | Dubbo vs @HttpExchange | **@HttpExchange** | 逐条呼叫非批量场景，HTTP 足够且调试方便 |

---

## 15. 自检清单

- [ ] 模块拆分：vhuan-call-api（接口 + DTO）与 vhuan-call（实现）两个 Maven 模块
- [ ] Dubbo 接口：CallDispatchApi（CLIENT_STREAM 服务端）+ CallProcessApi（BIDI STREAM 客户端）
- [ ] DTO 使用 JDK 21 Record 定义：CallDispatchRequest/Response、CallRequest/Response
- [ ] 通话状态机：6 态完整流转（IDLE → DIALING → RINGING → ANSWERED → IN_PROGRESS → ENDING → ENDED）
- [ ] 号码下发：CLIENT_STREAM 服务端逐条接收，虚拟线程异步发起 SIP 呼叫
- [ ] AI 通话：BIDI STREAM 推送音频（CallRequest.AUDIO）+ 接收转写/TTS/意图/槽位（CallResponse）
- [ ] 话术快照：通话开始时通过 AgentApi 拉取，通过 CallRequest.INIT 传给 ai-engine
- [ ] 媒体流管理：客户音频 → ai-engine，TTS 音频 → 客户，双向混音录音
- [ ] 坐席介入：监听（WebSocket）、切入（媒体流切换 + AI 静音）、切出（恢复 AI）
- [ ] 录音：通话中混音写入临时文件，结束后上传 MinIO
- [ ] 并发通道：通话开始 acquireChannel，通话结束 releaseChannel
- [ ] Kafka 事件：发布 10 个通话生命周期事件（call.created → call.recording.ready）
- [ ] WebSocket：netty-socketio 推送实时转写流到监控面板
- [ ] 通话超时：30 分钟自动挂断
- [ ] 预览式外呼：@HttpExchange 单次呼叫接口
- [ ] 错误码使用 `CallErrorCode`（6000-6999 区间）
- [ ] 数据表落在租户 Schema，5 张表（call_session/recording/transcript/intent_result/slot）
- [ ] Redis 缓存活跃通话集合、实时状态、转写缓冲、介入锁
- [ ] 不依赖 ai-engine 实现模块，通过 Dubbo @DubboReference 引用 CallProcessApi
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，进入 `vhuan-ai-engine` 详细设计。ai-engine 需实现 `CallProcessApi`（BIDI STREAM 服务端），接收 call-service 推送的音频流，返回 ASR 转写、NLU 意图、DM 状态变更、TTS 音频、槽位收集结果。
