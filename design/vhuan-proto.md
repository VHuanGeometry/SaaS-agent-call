# vhuan-proto 详细设计

> **模块**: vhuan-proto（Proto 定义模块）  
> **阶段**: 第一阶段 — 基础设施  
> **版本**: v1.0.0  
> **日期**: 2026-08-05  
> **状态**: 设计中

---

## 1. 设计目标

集中管理所有 gRPC 服务的 `.proto` 接口定义，通过 Maven 插件编译生成 Java 代码，供 `call-service`、`ai-engine-service`、`campaign-service`、`analytics-service` 等模块引用。

**设计原则**：
- 单一事实来源：所有 gRPC 契约在 `vhuan-proto` 中定义，服务的客户端和服务端依赖同一套生成代码
- 按通信场景拆分 `.proto` 文件，避免单文件过大
- 每个 `.proto` 文件定义独立的 `package` 和 `java_package`，避免类名冲突
- 租户上下文通过 gRPC Metadata 传递，不在每个 message 中重复携带

---

## 2. 模块结构

```
vhuan-proto/
├── pom.xml                                  # Maven 配置（protobuf-maven-plugin）
├── src/
│   └── main/
│       └── proto/                           # .proto 源文件
│           ├── common.proto                 # 公共类型：分页、响应、租户上下文
│           ├── call_engine.proto            # call ↔ ai-engine：Bidirectional Streaming
│           ├── campaign_call.proto          # campaign → call：Client Streaming
│           ├── engine_monitor.proto         # ai-engine → 监控面板：Server Streaming
│           └── analytics.proto             # 各服务 → analytics：指标上报
│
└── target/
    └── generated-sources/                   # Maven 插件编译生成
        └── protobuf/
            └── java/                        # 生成的 Java 代码
```

---

## 3. Proto 文件设计

### 3.1 common.proto — 公共类型

```protobuf
syntax = "proto3";

package vhuan.common;
option java_package = "com.vhuan.proto.common";
option java_multiple_files = true;

// ========== 分页请求 ==========
message PageRequest {
  int32 page = 1;        // 页码，从 1 开始
  int32 page_size = 2;   // 每页大小，默认 20，最大 100
  string sort_field = 3; // 排序字段
  string sort_order = 4; // 排序方向：asc / desc
}

// ========== 分页响应 ==========
message PageResponse {
  int64 total = 1;       // 总记录数
  int32 page = 2;        // 当前页码
  int32 page_size = 3;   // 每页大小
  int32 total_pages = 4; // 总页数
}

// ========== 统一响应 ==========
message BizResponse {
  int32 code = 1;        // 业务状态码（0=成功）
  string message = 2;    // 提示信息
  string trace_id = 3;   // 链路追踪 ID
}

// ========== 租户上下文（gRPC Metadata 传递，此处定义数据结构供参考） ==========
// 实际传递方式：客户端拦截器在 gRPC Metadata 中设置以下 key：
//   "tenant-id"       → string
//   "tenant-name"     → string
//   "plan-code"       → string
//   "user-id"         → string
// 服务端拦截器解析 Metadata 并设置 TenantContext

// ========== 空请求/空响应 ==========
message EmptyRequest {}
message EmptyResponse {}
```

### 3.2 call_engine.proto — 通话与 AI 引擎双向流

```protobuf
syntax = "proto3";

package vhuan.call.engine;
option java_package = "com.vhuan.proto.call.engine";
option java_multiple_files = true;

// ========== 通话引擎服务（Bidirectional Streaming） ==========
// 每个通话会话对应一个独立的双向流
// call-service 将用户音频推送给 ai-engine，ai-engine 回传转写结果和 TTS 指令
service CallEngineService {

  // 双向流：建立通话会话的音频流通道
  // 一个 stream 对应一个通话会话（session_id 在第一条消息中携带）
  rpc ProcessCall(stream CallRequest) returns (stream CallResponse);
}

// ========== 客户端 → 服务端（call-service → ai-engine） ==========
message CallRequest {

  // 请求类型（oneof 确保一条消息只包含一种类型）
  oneof payload {

    // 会话控制：建立/结束通话会话
    SessionControl session_control = 1;

    // 音频帧：用户语音数据
    AudioFrame audio_frame = 2;
  }
}

// 会话控制
message SessionControl {
  enum Action {
    UNKNOWN = 0;
    START = 1;   // 开始新会话
    END = 2;     // 结束会话
    PAUSE = 3;   // 暂停（坐席切入）
    RESUME = 4;  // 恢复（坐席切出）
  }

  Action action = 1;
  string session_id = 2;     // 通话会话 ID（雪花 ID）
  string agent_id = 3;       // AI Agent ID
  string script_id = 4;      // 话术模板 ID
  string tenant_id = 5;      // 租户 ID
  string phone = 6;          // 被叫号码
  AudioConfig audio_config = 7; // 音频配置
}

// 音频配置
message AudioConfig {
  enum AudioFormat {
    PCM_S16LE = 0;  // 16-bit 线性 PCM，小端
    OPUS = 1;       // Opus 编码
  }

  AudioFormat format = 1;
  int32 sample_rate = 2;   // 采样率（8000 / 16000）
  int32 channels = 3;      // 声道数（1=单声道）
  int32 frame_duration_ms = 4; // 帧时长（毫秒，20ms 推荐）
}

// 音频帧
message AudioFrame {
  bytes data = 1;            // 音频数据
  int64 sequence = 2;        // 帧序号（递增）
  int64 timestamp_ms = 3;    // 时间戳（毫秒）
  bool is_silence = 4;       // 是否静音帧（VAD 预判结果，可选）
}

// ========== 服务端 → 客户端（ai-engine → call-service） ==========
message CallResponse {

  oneof payload {

    // 会话确认
    SessionAck session_ack = 1;

    // ASR 转写结果（实时，逐句）
    TranscriptResult transcript = 2;

    // NLU 意图识别结果
    IntentResult intent = 3;

    // 对话管理指令
    DialogInstruction dialog_instruction = 4;

    // TTS 音频帧（ai-engine 合成的语音，需要 call-service 播放给用户）
    TtsAudioFrame tts_audio = 5;

    // 会话结束通知
    SessionEndNotification session_end = 6;

    // 错误通知
    ErrorInfo error = 7;
  }
}

// 会话确认
message SessionAck {
  string session_id = 1;
  bool accepted = 2;         // 是否接受会话
  string reject_reason = 3;  // 拒绝原因（如并发超限）
}

// ASR 转写结果
message TranscriptResult {
  string session_id = 1;
  string text = 2;           // 转写文本
  bool is_final = 3;         // 是否为最终结果（false=中间结果，true=最终结果）
  int64 start_ms = 4;        // 起始时间（毫秒，相对于会话开始）
  int64 end_ms = 5;          // 结束时间（毫秒）
  double confidence = 6;     // 置信度（0.0 ~ 1.0）
}

// NLU 意图识别结果
message IntentResult {
  string session_id = 1;
  string intent = 2;                      // 意图名称（如 "interested"、"hesitate"、"reject"）
  double confidence = 3;                  // 意图置信度
  map<string, string> slots = 4;          // 槽位键值对（如 {"name": "张三", "age": "30"}）
  string sentiment = 5;                   // 情感标签：positive / neutral / negative
  double sentiment_score = 6;             // 情感得分（-1.0 ~ 1.0）
}

// 对话管理指令
message DialogInstruction {
  string session_id = 1;
  string current_node = 2;                // 当前话术节点 ID
  string next_node = 3;                   // 下一个话术节点 ID（可为空，表示结束）
  string action = 4;                      // 动作：speak / wait / hangup / transfer / collect_slot
  string slot_key = 5;                    // 收集槽位时的 key（action=collect_slot 时有效）
  string slot_prompt = 6;                 // 收集槽位时的提示语
}

// TTS 音频帧
message TtsAudioFrame {
  string session_id = 1;
  bytes audio_data = 2;        // 合成音频数据
  int64 sequence = 3;          // 帧序号
  bool is_end = 4;             // 是否为最后一帧
  string text = 5;             // 当前帧对应的文本（用于坐席监控面板展示）
}

// 会话结束通知
message SessionEndNotification {
  string session_id = 1;
  string reason = 2;           // 结束原因：normal / hangup / timeout / error
  string final_intent = 3;     // 最终意向标签
  int32 duration_seconds = 4;  // 通话时长（秒）
  int32 turn_count = 5;        // 对话轮次
}

// 错误信息
message ErrorInfo {
  string session_id = 1;
  int32 error_code = 2;
  string error_message = 3;
}
```

**设计决策**：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 请求/响应使用 `oneof` | 一条消息携带多种类型 | 避免为每种事件单独建 stream，减少流数量，简化状态管理 |
| 音频帧格式 | 支持 PCM 和 Opus | PCM 保真度高用于 ASR，Opus 压缩率高用于 TTS 播放 |
| 租户 ID 传递 | 仅在 `SessionControl.START` 中携带一次 | 会话建立后 `session_id` 绑定租户，无需每条消息重复携带 |
| TTS 音频由 ai-engine 合成 | ai-engine 直接返回 TTS 音频帧 | 减少 call-service 的 TTS 调用，降低延迟和网络开销 |

### 3.3 campaign_call.proto — 任务调度到通话服务

```protobuf
syntax = "proto3";

package vhuan.campaign.call;
option java_package = "com.vhuan.proto.campaign.call";
option java_multiple_files = true;

import "common.proto";

// ========== 任务下发服务（Client Streaming） ==========
// campaign-service 将一批号码批量下发给 call-service
service CampaignCallService {

  // 客户端流：批量下发号码
  // campaign-service 作为客户端，流式推送号码；call-service 作为服务端，返回汇总结果
  rpc DispatchNumbers(stream DispatchRequest) returns (DispatchSummary);
}

// 下发请求
message DispatchRequest {

  oneof payload {

    // 批次头信息（第一条消息）
    BatchHeader batch_header = 1;

    // 号码明细（后续消息，可批量发送）
    NumberDetail number_detail = 2;
  }
}

// 批次头信息
message BatchHeader {
  string campaign_id = 1;      // 外呼任务 ID
  string batch_id = 2;         // 批次 ID
  string agent_id = 3;         // AI Agent ID
  string script_id = 4;        // 话术模板 ID
  string tenant_id = 5;        // 租户 ID
  int32 max_concurrent = 6;    // 最大并发数
  int32 retry_count = 7;       // 重试次数
  int32 retry_interval_seconds = 8; // 重试间隔（秒）
  string start_time = 9;       // 开始时间（ISO 8601，null=立即开始）
  string end_time = 10;        // 结束时间（ISO 8601，null=不限）
  CallStrategy strategy = 11;  // 外呼策略
}

// 外呼策略
enum CallStrategy {
  STRATEGY_UNKNOWN = 0;
  FIXED_TIME = 1;      // 定时外呼
  PREDICTIVE = 2;      // 预测式外呼
  PREVIEW = 3;         // 预览式外呼
}

// 号码明细
message NumberDetail {
  string contact_id = 1;       // 线索 ID
  string phone = 2;            // 电话号码
  string name = 3;             // 客户姓名（可选）
  map<string, string> variables = 4; // 话术变量（如 {"product": "保险A", "discount": "8折"}）
  int32 priority = 5;          // 优先级（0=最高，默认 0）
}

// 下发汇总
message DispatchSummary {
  string batch_id = 1;
  int32 total = 2;             // 总号码数
  int32 accepted = 3;          // 成功接收数
  int32 rejected = 4;          // 拒绝数（如号码格式错误、黑名单）
  repeated string reject_reasons = 5; // 拒绝原因列表
  vhuan.common.BizResponse response = 6; // 统一响应
}
```

### 3.4 engine_monitor.proto — AI 引擎到监控面板

```protobuf
syntax = "proto3";

package vhuan.engine.monitor;
option java_package = "com.vhuan.proto.engine.monitor";
option java_multiple_files = true;

// ========== 监控推送服务（Server Streaming） ==========
// ai-engine 将实时转写流推送给 call-service 的监控模块
// call-service 再通过 WebSocket 推送给前端监控面板
service EngineMonitorService {

  // 服务端流：订阅通话会话的实时转写和事件
  rpc SubscribeSession(SessionSubscribeRequest) returns (stream SessionEvent);
}

// 订阅请求
message SessionSubscribeRequest {
  string session_id = 1;       // 通话会话 ID
  string subscriber_id = 2;    // 订阅者 ID（坐席 ID）
  repeated string event_types = 3; // 订阅的事件类型（空=全部）
}

// 会话事件
message SessionEvent {

  oneof event {

    // 实时转写（逐句，中间结果 + 最终结果）
    TranscriptEvent transcript = 1;

    // 意图识别
    IntentEvent intent = 2;

    // 对话状态变化
    DialogStateEvent dialog_state = 3;

    // TTS 播放文本（坐席可见 AI 正在说的话）
    TtsPlayEvent tts_play = 4;

    // 槽位收集
    SlotCollectedEvent slot_collected = 5;

    // 会话状态变化
    SessionStatusEvent session_status = 6;
  }
}

// 转写事件
message TranscriptEvent {
  string session_id = 1;
  string text = 2;
  bool is_final = 3;
  string speaker = 4;          // "user" / "ai"
  int64 timestamp_ms = 5;
}

// 意图事件
message IntentEvent {
  string session_id = 1;
  string intent = 2;
  double confidence = 3;
  int64 timestamp_ms = 4;
}

// 对话状态事件
message DialogStateEvent {
  string session_id = 1;
  string from_node = 2;        // 来源节点
  string to_node = 3;          // 目标节点
  string action = 4;           // 当前动作
  int64 timestamp_ms = 5;
}

// TTS 播放事件
message TtsPlayEvent {
  string session_id = 1;
  string text = 2;             // 正在播放的文本
  int64 timestamp_ms = 3;
}

// 槽位收集事件
message SlotCollectedEvent {
  string session_id = 1;
  string key = 2;
  string value = 3;
  int64 timestamp_ms = 4;
}

// 会话状态事件
message SessionStatusEvent {
  string session_id = 1;
  enum Status {
    UNKNOWN = 0;
    CONNECTING = 1;   // 正在连接
    RINGING = 2;      // 振铃中
    ANSWERED = 3;     // 已接听
    IN_PROGRESS = 4;  // 对话中
    AGENT_JOINED = 5; // 坐席已切入
    AGENT_LEFT = 6;   // 坐席已切出
    ENDED = 7;        // 已结束
  }
  Status status = 2;
  int64 timestamp_ms = 3;
}
```

### 3.5 analytics.proto — 指标上报

```protobuf
syntax = "proto3";

package vhuan.analytics;
option java_package = "com.vhuan.proto.analytics";
option java_multiple_files = true;

import "common.proto";

// ========== 指标上报服务 ==========
// 各服务（call-service、ai-engine-service）将指标推送给 analytics-service
service AnalyticsService {

  // 上报通话指标（Unary，高频小包）
  rpc ReportCallMetric(CallMetric) returns (vhuan.common.BizResponse);

  // 批量上报通话指标（Client Streaming，提高吞吐）
  rpc ReportCallMetricsBatch(stream CallMetric) returns (vhuan.common.BizResponse);

  // 上报 AI 引擎性能指标
  rpc ReportEngineMetric(EngineMetric) returns (vhuan.common.BizResponse);
}

// 通话指标
message CallMetric {
  string tenant_id = 1;
  string campaign_id = 2;
  string session_id = 3;
  string agent_id = 4;
  string phone = 5;            // 脱敏后号码

  // 通话结果
  enum CallResult {
    UNKNOWN = 0;
    ANSWERED = 1;      // 已接听
    NO_ANSWER = 2;     // 无应答
    BUSY = 3;          // 忙线
    REJECTED = 4;      // 拒接
    INVALID = 5;       // 空号/无效号码
    BLACKLIST = 6;     // 黑名单拦截
  }
  CallResult result = 6;

  int32 duration_seconds = 7;  // 通话时长
  int32 turn_count = 8;        // 对话轮次
  string final_intent = 9;     // 最终意向
  int64 timestamp_ms = 10;     // 通话结束时间戳
}

// AI 引擎性能指标
message EngineMetric {
  string tenant_id = 1;
  string session_id = 2;
  string model_instance = 3;   // 模型实例 ID

  // ASR 指标
  int32 asr_total_requests = 4;
  double asr_avg_latency_ms = 5;
  double asr_avg_confidence = 6;

  // NLU 指标
  int32 nlu_total_requests = 7;
  double nlu_avg_latency_ms = 8;

  // TTS 指标
  int32 tts_total_chars = 9;
  double tts_avg_first_audio_ms = 10; // 首音延迟

  int64 timestamp_ms = 11;
}
```

---

## 4. gRPC 租户上下文传递

### 4.1 传递机制

gRPC 通过 **Metadata**（类似于 HTTP Header）传递租户上下文，无需在每个 message 中重复携带 `tenant_id`。

```
客户端拦截器                         服务端拦截器
┌──────────────────┐                ┌──────────────────┐
│ 读取 TenantContext │                │ 解析 Metadata     │
│ 设置 Metadata:     │  ──gRPC 请求──▶ │ 设置 TenantContext │
│   tenant-id        │                │   (Scoped Values) │
│   tenant-name      │                │                  │
│   plan-code        │  ◀──gRPC 响应── │                  │
│   user-id          │                │                  │
└──────────────────┘                └──────────────────┘
```

### 4.2 拦截器实现要点

```java
// 客户端拦截器（在 vhuan-common 中提供基类）
public class TenantClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next
    ) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(...) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                TenantContext ctx = TenantContextHolder.get();
                headers.put(Metadata.Key.of("tenant-id", ASCII_STRING_MARSHALLER), ctx.tenantId());
                headers.put(Metadata.Key.of("tenant-name", ASCII_STRING_MARSHALLER), ctx.tenantName());
                headers.put(Metadata.Key.of("plan-code", ASCII_STRING_MARSHALLER), ctx.planCode());
                super.start(responseListener, headers);
            }
        };
    }
}

// 服务端拦截器（在 vhuan-common 中提供基类）
public class TenantServerInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next
    ) {
        String tenantId = headers.get(Metadata.Key.of("tenant-id", ASCII_STRING_MARSHALLER));
        String tenantName = headers.get(Metadata.Key.of("tenant-name", ASCII_STRING_MARSHALLER));
        String planCode = headers.get(Metadata.Key.of("plan-code", ASCII_STRING_MARSHALLER));

        TenantContext ctx = new TenantContext(tenantId, tenantName, planCode, null, null);
        return ScopedValue.where(TenantContextHolder.getScopedValue(), ctx)
            .call(() -> next.startCall(call, headers));
    }
}
```

---

## 5. Maven 配置

### 5.1 pom.xml

```xml
<dependencies>
    <!-- gRPC 核心依赖 -->
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-netty-shaded</artifactId>
    </dependency>
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-protobuf</artifactId>
    </dependency>
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-stub</artifactId>
    </dependency>

    <!-- Protobuf Java 运行时 -->
    <dependency>
        <groupId>com.google.protobuf</groupId>
        <artifactId>protobuf-java</artifactId>
    </dependency>

    <!-- Jakarta 注解（gRPC 服务端需要 @GrpcService） -->
    <dependency>
        <groupId>jakarta.annotation</groupId>
        <artifactId>jakarta.annotation-api</artifactId>
    </dependency>
</dependencies>

<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.1</version>
        </extension>
    </extensions>
    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <configuration>
                <protocArtifact>com.google.protobuf:protoc:3.25.3:exe:${os.detected.classifier}</protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.64.0:exe:${os.detected.classifier}</pluginArtifact>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>compile-custom</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 5.2 引用方式

其他模块在 `pom.xml` 中引入 `vhuan-proto` 即可获得所有生成的 gRPC 代码：

```xml
<dependency>
    <groupId>com.vhuan</groupId>
    <artifactId>vhuan-proto</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 6. gRPC 服务端口规划

| 服务 | gRPC 端口 | 说明 |
|------|-----------|------|
| call-service | 9100 | 被 campaign-service 调用（Client Streaming）、被 ai-engine 回调（Bidi） |
| ai-engine-service | 9101 | 被 call-service 调用（Bidi）、被监控面板订阅（Server Streaming） |
| analytics-service | 9102 | 被各服务上报指标（Unary / Client Streaming） |

**设计决策**：gRPC 端口与 HTTP 端口分离，便于独立配置防火墙规则和负载均衡策略。gRPC 流量走独立的 Service/LB，不经过 Gateway。

---

## 7. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| Proto 管理方式 | 统一模块 vs 各服务独立 | 统一模块 `vhuan-proto` | 单一事实来源，避免各服务 proto 版本不一致 |
| 租户上下文传递 | Message 字段 vs Metadata | gRPC Metadata | 减少重复字段，利用 gRPC 标准机制，拦截器统一处理 |
| 音频流模式 | 单 stream 多 oneof vs 多 stream | 单 stream + oneof | 降低 stream 管理复杂度，一条流承载所有会话事件 |
| 监控推流路径 | ai-engine → 监控面板 vs ai-engine → call → 监控面板 | ai-engine → call → WebSocket → 监控面板 | call-service 统一管理 WebSocket 连接，ai-engine 不直接暴露给前端 |
| gRPC 端口策略 | HTTP 同端口 vs 独立端口 | 独立端口 | 便于独立配置防火墙、负载均衡和监控 |

---

## 8. 自检清单

- [ ] 5 个 `.proto` 文件覆盖所有 gRPC 通信场景
- [ ] 每个 `.proto` 使用独立的 `package` 和 `java_package`
- [ ] `CallEngineService` 使用 Bidirectional Streaming，`oneof` 覆盖 7 种请求 + 7 种响应类型
- [ ] `CampaignCallService` 使用 Client Streaming，支持批次头 + 号码明细
- [ ] `EngineMonitorService` 使用 Server Streaming，覆盖 6 种事件类型
- [ ] `AnalyticsService` 同时提供 Unary 和 Client Streaming 两种上报方式
- [ ] 租户上下文通过 gRPC Metadata 传递，不在 message 中重复携带
- [ ] `protobuf-maven-plugin` 配置正确，编译生成 Java 代码
- [ ] gRPC 端口与 HTTP 端口分离，互不冲突
- [ ] `vhuan-proto` 作为独立模块，其他模块通过 Maven 依赖引用

---

> **下一步**：本设计确认后，进入 `vhuan-gateway` 详细设计。