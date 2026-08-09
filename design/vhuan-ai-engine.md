# vhuan-ai-engine 详细设计

> **模块**: vhuan-ai-engine（AI 引擎服务）  
> **阶段**: 第二阶段 — 核心业务链路  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供 AI 对话核心能力：流式语音识别（ASR）、自然语言理解（NLU）、对话管理（DM）、流式语音合成（TTS）。作为 Dubbo Triple BIDIRECTIONAL_STREAM 的服务端，接收 call-service 推送的客户音频流，实时返回转写文本、意图分类、对话状态变更、槽位收集结果和合成语音。是核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）的最后一环。

**职责边界**：
- ASR 识别：流式接收 PCM 音频，实时输出转写文本，支持 VAD 断句
- NLU 意图：对转写文本做意图分类、槽位提取、情感分析
- 对话管理：基于话术状态机执行节点跳转、上下文记忆、多轮策略
- TTS 合成：将回复话术流式合成为语音，支持多音色、语速调节、SSML
- 模型路由：按租户、场景、成本路由到不同 ASR/NLU/TTS 模型实例
- 会话管理：每个通话会话绑定一个虚拟线程，Scoped Values 传递租户上下文

**非职责**：
- 不管理 Agent 与话术配置（由 `vhuan-agent` 负责），话术配置通过 CallRequest.INIT 传入
- 不处理 SIP 信令和媒体流转发（由 `vhuan-call` 负责），只接收和返回音频数据
- 不直接推送数据到前端监控面板（由 `vhuan-call` 通过 WebSocket 中转）
- 不发起外呼调度（由 `vhuan-campaign` 负责）
- 不管理用户认证与权限（由 `vhuan-auth` 和 `vhuan-gateway` 负责）

**与 call-service 的分工**：
- **ai-engine 管 AI**：听到什么（ASR）、理解什么（NLU）、走到哪（DM）、说什么（TTS）
- **call-service 管通道**：音频怎么传（RTP/SIP）、录什么音（混音录音）、推什么给前端（WebSocket）
- ai-engine 的所有输出通过 BIDI STREAM 返回给 call-service，由 call-service 决定如何分发（写库、WebSocket 推送、Kafka 发布）

**不直接暴露给前端**（AGENTS.md 约束）：ai-engine 不提供 HTTP 接口，不持有 WebSocket 连接，前端监控面板的所有实时数据均由 call-service 中转推送。

---

## 2. 模块结构

```
vhuan-ai-engine/
├── pom.xml
├── src/main/java/com/vhuan/ai/
│   ├── AiEngineApplication.java                      # 启动类
│   │
│   ├── dubbo/
│   │   └── CallProcessServiceImpl.java               # BIDI STREAM 服务端实现
│   │
│   ├── asr/
│   │   ├── AsrService.java                           # ASR 识别服务接口
│   │   ├── AsrModelAdapter.java                       # ASR 模型适配器接口
│   │   ├── impl/
│   │   │   ├── AsrServiceImpl.java
│   │   │   ├── SenseVoiceAdapter.java                # SenseVoice 模型适配
│   │   │   └── WhisperAdapter.java                   # Whisper 模型适配
│   │   └── vad/
│   │       └── VadDetector.java                       # VAD 语音活动检测
│   │
│   ├── nlu/
│   │   ├── NluService.java                            # NLU 理解服务接口
│   │   ├── IntentClassifier.java                     # 意图分类器接口
│   │   ├── SlotExtractor.java                         # 槽位提取器接口
│   │   └── impl/
│   │       ├── NluServiceImpl.java
│   │       ├── SmallModelClassifier.java              # 小模型意图分类（快速）
│   │       ├── LlmFallbackClassifier.java             # LLM 兜底分类（高精度）
│   │       └── RegexSlotExtractor.java                # 正则槽位提取
│   │
│   ├── dm/
│   │   ├── DialogManager.java                         # 对话管理引擎
│   │   ├── ScriptExecutor.java                        # 话术状态机执行器
│   │   ├── ContextMemory.java                         # 上下文记忆管理
│   │   ├── VariableResolver.java                     # 变量替换引擎
│   │   └── impl/
│   │       ├── DialogManagerImpl.java
│   │       ├── ScriptExecutorImpl.java
│   │       ├── ContextMemoryImpl.java
│   │       └── VariableResolverImpl.java
│   │
│   ├── tts/
│   │   ├── TtsService.java                            # TTS 合成服务接口
│   │   ├── TtsModelAdapter.java                        # TTS 模型适配器接口
│   │   └── impl/
│   │       ├── TtsServiceImpl.java
│   │       ├── CosyVoiceAdapter.java                  # CosyVoice 模型适配
│   │       └── ChatTtsAdapter.java                    # ChatTTS 模型适配
│   │
│   ├── router/
│   │   ├── ModelRouter.java                           # 模型路由器
│   │   └── impl/
│   │       └── ModelRouterImpl.java
│   │
│   ├── pipeline/
│   │   ├── CallPipeline.java                          # 通话处理管道（ASR→NLU→DM→TTS）
│   │   └── PipelineContext.java                       # 管道上下文（会话级状态）
│   │
│   ├── session/
│   │   ├── SessionManager.java                        # 会话管理器
│   │   └── SessionContext.java                        # 会话上下文
│   │
│   ├── remote/
│   │   └── AgentClient.java                           # @HttpExchange 调用 agent-service（知识库检索）
│   │
│   ├── model/
│   │   ├── AsrResult.java                             # ASR 识别结果
│   │   ├── NluResult.java                             # NLU 理解结果
│   │   ├── DmResult.java                              # 对话管理结果
│   │   ├── TtsResult.java                             # TTS 合成结果
│   │   └── ScriptSnapshot.java                        # 话术配置快照（反序列化用）
│   │
│   ├── enums/
│   │   ├── AsrStatus.java                             # ASR 状态（PARTIAL/FINAL）
│   │   ├── IntentType.java                            # 意图类型（与 agent 模块一致）
│   │   └── ModelProvider.java                          # 模型供应商
│   │
│   └── config/
│       ├── AiEngineProperties.java                    # 服务配置
│       ├── DubboConfig.java                           # Dubbo 服务端配置
│       └── ModelConfig.java                           # 模型实例配置
│
└── src/main/resources/
    └── application.yml
```

**包设计要点**：
- 四大核心模块各自独立包（`asr/`、`nlu/`、`dm/`、`tts/`），每个模块定义接口 + 多个适配器实现
- `pipeline/` 包编排 ASR→NLU→DM→TTS 的执行顺序，是 BIDI STREAM 服务端的核心调度逻辑
- `router/` 包根据租户、场景、成本选择模型实例，解耦模型选择与业务逻辑
- `session/` 包管理会话级状态（话术快照、上下文记忆、槽位状态）
- `model/` 包定义各模块的输入输出模型，以及话术快照反序列化类

---

## 3. Dubbo 接口实现（BIDI STREAM 服务端）

### 3.1 接口契约

ai-engine 实现 `CallProcessApi`（定义在 `vhuan-call-api` 模块），作为 BIDI STREAM 的服务端。

```java
/**
 * AI 通话处理 — Dubbo Triple BIDIRECTIONAL_STREAM 服务端实现
 */
@DubboService
public class CallProcessServiceImpl implements CallProcessApi {

    @Override
    public StreamObserver<CallRequest> processCall(
            StreamObserver<CallResponse> responseObserver) {

        // 返回请求观察者，call-service 通过它推送音频流
        return new StreamObserver<>() {
            private PipelineContext context;

            @Override
            public void onNext(CallRequest request) {
                // 在虚拟线程中处理，避免阻塞 Dubbo I/O 线程
                Thread.startVirtualThread(() -> {
                    ScopedValue.where(TenantContextHolder.getScopedValue(),
                        context.getTenantContext()).run(() -> {
                        handleRequest(request, responseObserver);
                    });
                });
            }

            @Override
            public void onError(Throwable t) {
                log.error("通话处理流异常: sessionId={}",
                    context != null ? context.getSessionId() : "null", t);
                if (context != null) {
                    SessionManager.remove(context.getSessionId());
                }
            }

            @Override
            public void onCompleted() {
                // call-service 推送完毕（通话结束）
                if (context != null) {
                    SessionManager.remove(context.getSessionId());
                }
                responseObserver.onCompleted();
            }
        };
    }
}
```

### 3.2 请求分发

```java
private void handleRequest(CallRequest request, 
        StreamObserver<CallResponse> responseObserver) {
    
    switch (request.type()) {
        case INIT -> {
            // 初始化：反序列化话术快照，创建会话上下文
            context = pipeline.initialize(request);
            log.info("通话会话初始化: sessionId={}", request.sessionId());
        }
        case AUDIO -> {
            // 音频流：推入处理管道
            pipeline.processAudio(request, context, responseObserver);
        }
        case CONTROL -> {
            // 控制指令：START/STOP/INTERRUPT/RESUME
            pipeline.handleControl(request, context, responseObserver);
        }
    }
}
```

---

## 4. ASR 模块

### 4.1 设计要点

- **流式识别**：接收 PCM 16kHz 16bit mono 音频块，实时输出转写文本
- **VAD 断句**：检测语音活动（有人说话）与静音段（说完一句话），按句发送识别结果
- **多模型适配**：支持 SenseVoice（阿里达摩院）和 Whisper（OpenAI）两种模型
- **延迟要求**：首字延迟 < 300ms

### 4.2 AsrService 接口

```java
public interface AsrService {

    /**
     * 流式识别
     * 持续接收音频块，通过回调返回识别结果
     * 
     * @param audioData PCM 音频数据
     * @param sessionId 通话会话 ID
     * @param callback 识别结果回调（部分结果 / 最终结果）
     */
    void recognize(byte[] audioData, String sessionId, AsrCallback callback);

    /**
     * 结束识别（通话结束时清理资源）
     */
    void finish(String sessionId);

    /**
     * ASR 结果回调接口
     */
    @FunctionalInterface
    interface AsrCallback {
        /**
         * @param result 识别结果
         * @param isFinal true=句子最终结果，false=部分结果（实时更新中）
         */
        void onResult(AsrResult result, boolean isFinal);
    }
}
```

### 4.3 AsrResult

```java
/**
 * ASR 识别结果
 */
public record AsrResult(
    String text,           // 转写文本
    double confidence,     // 置信度（0-1）
    long audioStartMs,     // 音频起始时间戳（毫秒）
    long audioEndMs        // 音频结束时间戳
) {}
```

### 4.4 VAD 断句策略

```
持续接收 PCM 音频块
        │
        ├── VAD 检测到语音活动 → 开始累积音频
        │
        ├── 持续累积 → 实时推送部分识别结果（PARTIAL）
        │              通过 callback.onResult(result, false)
        │
        ├── VAD 检测到静音段（≥ 500ms）→ 句子结束
        │              推送最终识别结果（FINAL）
        │              通过 callback.onResult(result, true)
        │
        └── 静音超过 3s → 判定用户沉默（触发 SILENCE 意图）
```

**设计决策**：VAD 静音判定阈值设为 500ms（一句话说完）和 3s（用户沉默）。500ms 断句保证实时性，3s 沉默触发兜底回复，避免 AI 空等。

### 4.5 模型适配器

```java
public interface AsrModelAdapter {

    /** 模型标识（如 sensevoice / whisper-large） */
    String getModelId();

    /** 初始化识别会话 */
    void initSession(String sessionId);

    /** 推送音频块，返回识别结果 */
    List<AsrResult> processAudio(byte[] audioData, String sessionId);

    /** 结束识别会话 */
    void closeSession(String sessionId);
}
```

| 模型 | 延迟 | 准确率 | 适用场景 |
|------|------|--------|----------|
| SenseVoice | < 200ms 首字 | 中文准确率 96%+ | 默认中文外呼场景 |
| Whisper-large | < 300ms 首字 | 多语言 94%+ | 多语言/方言场景 |

---

## 5. NLU 模块

### 5.1 设计要点

- **意图分类**：小模型快速分类（< 200ms）+ LLM 兜底（高精度但慢）
- **槽位提取**：正则 + 规则匹配，从用户回答中提取结构化信息
- **情感分析**：可选，判断用户情绪状态（积极/中性/消极）
- **意图标签**：根据 Agent 配置的 `intentTags` 做分类

### 5.2 NluService 接口

```java
public interface NluService {

    /**
     * 理解用户输入
     * 
     * @param text ASR 转写文本
     * @param context 管道上下文（含意图标签集、当前槽位状态）
     * @return NLU 结果（意图 + 槽位 + 情感）
     */
    NluResult understand(String text, PipelineContext context);
}
```

### 5.3 NluResult

```java
/**
 * NLU 理解结果
 */
public record NluResult(
    String intent,              // 意图分类（POSITIVE / HESITANT / NEGATIVE / ASK_QUESTION / CONFIRM / FALLBACK / SILENCE）
    double confidence,          // 置信度（0-1）
    Map<String, String> slots,  // 提取的槽位（slotKey → slotValue）
    String sentiment            // 情感（POSITIVE / NEUTRAL / NEGATIVE）
) {}
```

### 5.4 双层意图分类策略

```
ASR 最终结果
    │
    ▼
┌──────────────────────────────┐
│ 1. 小模型快速分类              │  意图分类小模型（< 200ms）
│    输入：转写文本 + 意图标签集  │  基于关键词匹配 + 简单分类器
│    输出：意图 + 置信度          │
└────────────┬────────────────┘
             ▼
         置信度 ≥ 0.8？
         ┌──┴──┐
        是     否
         │      │
         │      ▼
         │   ┌──────────────────────────────┐
         │   │ 2. LLM 兜底分类               │  大语言模型（< 1500ms）
         │   │    输入：转写文本 + 话术上下文   │  高精度但延迟高
         │   │    输出：意图 + 置信度          │
         │   └────────────┬────────────────┘
         │                │
         └────────────────┘
                          │
                          ▼
                    返回 NluResult
```

**设计决策**：双层分类策略平衡速度与精度。小模型处理 80% 的明确意图（"好的"/"不需要"/"再说吧"），LLM 兜底处理模糊或复杂表述。置信度阈值 0.8 根据实际测试调优。

### 5.5 槽位提取

```java
public interface SlotExtractor {

    /**
     * 从用户输入中提取槽位
     *
     * @param text 用户输入文本
     * @param slotDefinitions 槽位定义（来自话术节点的 Slot 列表）
     * @return 提取到的槽位（slotKey → slotValue）
     */
    Map<String, String> extract(String text, List<SlotDefinition> slotDefinitions);
}
```

| 槽位类型 | 提取规则 | 示例 |
|----------|----------|------|
| PHONE | 正则 `^1\d{10}$` | "我的号码是13800138000" → phone=13800138000 |
| DATE | 日期解析（Hutool DateUtil） | "明天下午三点" → appointment_time=2026-08-10 15:00 |
| ENUM | 关键词匹配 | "上午" / "下午" → preferred_time=MORNING/AFTERNOON |
| STRING | 整句作为值 | "我叫张三" → name=张三 |

---

## 6. 对话管理（DM）

### 6.1 设计要点

- **话术状态机执行**：基于 agent-service 配置的话术树（ScriptSnapshot），在本地执行节点跳转
- **上下文记忆**：维护对话历史、槽位状态、当前节点，支持多轮对话
- **变量替换**：将话术中的 `${变量名}` 替换为实际值
- **知识库检索**：用户提问超出话术路径时，检索 FAQ 知识库辅助回复
- **不回查 agent-service**：通话开始时收到话术快照后，全流程在本地执行

### 6.2 DialogManager 接口

```java
public interface DialogManager {

    /**
     * 初始化对话
     * 
     * @param snapshot 话术配置快照（从 CallRequest.INIT 获取）
     * @param variables 自定义变量（从联系人带入）
     * @return 初始化后的上下文（含当前节点=根节点）
     */
    PipelineContext initialize(ScriptSnapshot snapshot, Map<String, String> variables);

    /**
     * 处理一轮对话
     * 
     * @param nluResult NLU 理解结果
     * @param context 管道上下文
     * @return 对话管理结果（回复话术 + 动作 + 状态变更）
     */
    DmResult processTurn(NluResult nluResult, PipelineContext context);

    /**
     * 处理控制指令
     * 
     * @param command 控制指令（INTERRUPT/RESUME）
     * @param context 管道上下文
     */
    void handleControl(String command, PipelineContext context);
}
```

### 6.3 DmResult

```java
/**
 * 对话管理结果
 */
public record DmResult(
    String replyText,           // 回复话术（已变量替换）
    String actionType,          // 节点动作（NONE/JUMP/COLLECT_SLOT/HANGUP/TRANSFER_HUMAN/TAG_INTENT...）
    String actionParams,        // 动作参数（JSON）
    String currentNodeId,       // 当前节点 ID（状态变更后）
    Map<String, String> slots,  // 本轮收集的槽位
    String intentTag,           // 意向标签（TAG_INTENT 动作时有效）
    boolean shouldEndCall       // 是否应结束通话
) {}
```

### 6.4 话术状态机执行

```
收到 NluResult（意图 + 槽位）
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 获取当前节点                       │
│    context.currentNodeId              │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 根据节点类型处理                    │
└────────────┬─────────────────────────┘
             │
    ┌────────┼────────────────┐
    ▼        ▼                ▼
INTENT_    SLOT_           其他
CLASSIFY   FILLING         节点
    │        │                │
    ▼        ▼                ▼
┌────────┐ ┌──────────┐  ┌──────────┐
│ 匹配    │ │ 槽位校验  │  │ 执行动作  │
│ 转换边  │ │ 是否完成  │  │ 替换变量  │
│        │ │          │  │ 生成回复  │
│ intent │ │ required │  │          │
│ + cond │ │ 槽位满足？│  │          │
└───┬────┘ └───┬──────┘  └───┬──────┘
    │          │              │
    ▼          ▼              ▼
┌──────────────────────────────────────┐
│ 3. 跳转到目标节点                     │
│    context.currentNodeId = targetNodeId│
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 变量替换                            │
│    replyText = replaceVariables(replyText)│
│    ${customer_name} → "张三"           │
│    ${product_name} → "智能POS机"        │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 5. 执行节点动作                        │
│    TAG_INTENT → 标记意向标签            │
│    HANGUP → shouldEndCall = true      │
│    TRANSFER_HUMAN → 通知 call-service   │
│    COLLECT_SLOT → 等待下一轮用户输入     │
└────────────┬─────────────────────────┘
             ▼
       返回 DmResult
```

### 6.5 上下文记忆

```java
/**
 * 会话级上下文 — 每个通话会话独立维护
 */
public class PipelineContext {
    /** 通话会话 ID */
    String sessionId;
    /** 租户上下文 */
    TenantContext tenantContext;
    /** 话术配置快照 */
    ScriptSnapshot scriptSnapshot;
    /** 当前节点 ID */
    String currentNodeId;
    /** 对话历史（按轮次记录） */
    List<TurnRecord> dialogHistory;
    /** 槽位状态（slotKey → slotValue） */
    Map<String, String> slots;
    /** 全局变量 + 联系人变量 + 槽位变量 */
    Map<String, String> variables;
    /** 兜底重试计数器 */
    int fallbackRetryCount;
    /** 是否被坐席打断 */
    boolean interrupted;
}

/**
 * 单轮对话记录
 */
public record TurnRecord(
    String role,        // AI / CUSTOMER / AGENT
    String text,        // 说话内容
    String intent,      // 意图（仅 CUSTOMER 轮次）
    String nodeId        // AI 所在话术节点
) {}
```

### 6.6 变量替换

```java
public interface VariableResolver {

    /**
     * 替换话术中的变量引用
     * 
     * @param template 原始模板（如 "您好${customer_name}，我是${company_name}的AI助手"）
     * @param variables 变量映射表
     * @return 替换后的文本（如 "您好张三，我是汇智科技的AI助手"）
     */
    String resolve(String template, Map<String, String> variables);
}
```

变量优先级：槽位变量 > 联系人变量 > 全局变量 > 系统变量 > 默认值。

---

## 7. TTS 模块

### 7.1 设计要点

- **流式合成**：文本输入后立即开始合成首段音频，不等整句合成完毕
- **多音色适配**：支持 CosyVoice 和 ChatTTS，根据 Agent 绑定的音色配置选择
- **SSML 支持**：支持语音标记语言（语速调节、停顿、强调）
- **延迟要求**：首音延迟 < 500ms

### 7.2 TtsService 接口

```java
public interface TtsService {

    /**
     * 流式语音合成
     * 
     * @param text 待合成文本
     * @param voiceConfig 音色配置（provider + voiceId + speed + pitch）
     * @param sessionId 通话会话 ID
     * @param callback 合成结果回调（分块返回音频）
     */
    void synthesize(String text, VoiceConfig voiceConfig, 
                    String sessionId, TtsCallback callback);

    /**
     * TTS 合成回调
     */
    @FunctionalInterface
    interface TtsCallback {
        /**
         * @param audioData 音频数据块（PCM）
         * @param isLast true=最后一块，false=后续还有
         */
        void onAudio(byte[] audioData, boolean isLast);
    }
}
```

### 7.3 VoiceConfig

```java
/**
 * 音色配置 — 从 AgentVoice.voiceConfig（JSON）反序列化
 */
public record VoiceConfig(
    String provider,       // TTS 服务商（cosyvoice / chattts / azure）
    String voiceId,        // 音色 ID
    double speed,         // 语速（0.5-2.0，默认 1.0）
    double pitch,         // 音调（-10 到 10，默认 0）
    String ssml           // SSML 标记（可选）
) {}
```

### 7.4 模型适配器

```java
public interface TtsModelAdapter {

    /** 模型标识 */
    String getModelId();

    /** 流式合成 */
    void synthesize(String text, VoiceConfig config, 
                    String sessionId, TtsCallback callback);
}
```

| 模型 | 首音延迟 | 音质 | 中文支持 | 适用场景 |
|------|----------|------|----------|----------|
| CosyVoice | < 400ms | 高 | 优秀 | 默认中文场景 |
| ChatTTS | < 500ms | 中高 | 优秀 | 成本敏感场景 |

---

## 8. 模型路由

### 8.1 路由策略

```java
public interface ModelRouter {

    /** 路由 ASR 模型 */
    AsrModelAdapter routeAsr(String tenantId, String scenario);

    /** 路由 NLU 模型（小模型 / LLM） */
    IntentClassifier routeNlu(String tenantId, String scenario);

    /** 路由 TTS 模型 */
    TtsModelAdapter routeTts(String tenantId, VoiceConfig voiceConfig);
}
```

### 8.2 路由维度

| 维度 | 说明 | 示例 |
|------|------|------|
| 租户套餐 | 高级套餐路由到高精度模型 | ENTERPRISE → Whisper-large，STARTER → SenseVoice |
| 场景 | 根据通话场景选择 | 方言场景 → Whisper（多语言能力强） |
| 成本 | 按成本优化 | 低价值任务 → 小模型 NLU，高价值 → LLM |
| 负载 | 按模型实例负载均衡 | 多实例时选择负载最低的 |

### 8.3 路由配置

```yaml
ai-engine:
  model-router:
    asr:
      default: sensevoice          # 默认 ASR 模型
      enterprise: whisper-large     # 企业版套餐使用 Whisper
    nlu:
      default: small-model          # 默认小模型
      fallback: qwen-plus          # 兜底 LLM
    tts:
      default: cosyvoice            # 默认 TTS
```

---

## 9. 处理管道（Pipeline）

### 9.1 管道流程

```
call-service 推送 CallRequest.AUDIO（PCM 音频块）
        │
        ▼
┌──────────────────────────────────────────────────┐
│                    Pipeline                        │
│                                                    │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐       │
│  │  ASR    │───▶│  NLU    │───▶│  DM     │       │
│  │ 流式识别 │    │ 意图分类 │    │ 状态机   │       │
│  │ VAD断句  │    │ 槽位提取 │    │ 节点跳转 │       │
│  └─────────┘    └─────────┘    └────┬────┘       │
│                                     │             │
│                              ┌──────▼──────┐     │
│                              │  变量替换     │     │
│                              │  生成回复话术  │     │
│                              └──────┬──────┘     │
│                                     │             │
│                              ┌──────▼──────┐     │
│                              │    TTS      │     │
│                              │  流式合成    │     │
│                              └──────┬──────┘     │
│                                     │             │
└─────────────────────────────────────┼─────────────┘
                                      │
                                      ▼
        通过 CallResponse 返回给 call-service
        ├── ASR 类型：转写文本
        ├── NLU 类型：意图 + 置信度
        ├── DM 类型：当前节点 ID
        ├── SLOT 类型：槽位 key + value
        └── TTS 类型：合成音频
```

### 9.2 完整一轮对话处理

```
1. ASR 收到音频块
   │
   ├── VAD 检测到句子结束
   │   └── 推送 FINAL 转写结果
   │       └── 回调 CallResponse(ASR, text="好的我想了解一下")
   │
   ▼
2. NLU 理解转写文本
   │
   ├── 小模型分类 → intent=POSITIVE, confidence=0.92
   │   └── 回调 CallResponse(NLU, intent=POSITIVE, confidence=0.92)
   │
   ▼
3. DM 处理意图
   │
   ├── 当前节点 INTENT_CLASSIFY
   ├── 匹配 Transition: intent=POSITIVE → target=SLOT_FILLING
   ├── 跳转到槽位收集节点
   ├── 生成回复: "好的，请问您的手机号码是多少？"
   ├── 变量替换（无变量引用，原样输出）
   ├── 回调 CallResponse(DM, currentNodeId=SLOT_FILLING)
   │
   ▼
4. TTS 合成回复话术
   │
   ├── 流式合成 "好的，请问您的手机号码是多少？"
   ├── 分块返回音频
   │   └── 回调 CallResponse(TTS, audioData=...)
   │
   ▼
5. 等待下一轮用户输入
```

### 9.3 并行优化

ASR 和 TTS 采用流式模式，与对话管理管道并行工作：

```
时间轴 ──────────────────────────────────────────▶

ASR:  [接收音频][部分结果1][部分结果2][最终结果]
                                          │
NLU:                                     [分类][槽位]
                                                    │
DM:                                                 [跳转][生成回复]
                                                              │
TTS:                                                           [合成首段][合成中][合成完]

端到端延迟 = ASR断句延迟 + NLU分类延迟 + DM处理延迟 + TTS首音延迟
           ≈ 500ms + 200ms + 50ms + 400ms ≈ 1.2s
```

**关键优化**：ASR 在 VAD 检测到句子结束时立即提交 NLU，不等后续音频。TTS 在收到完整回复文本后立即开始合成首段，不等整句合成完毕即开始播放。端到端延迟控制在 2s 以内。

---

## 10. 会话管理

### 10.1 SessionManager

```java
public class SessionManager {

    /**
     * 创建会话上下文
     */
    PipelineContext createSession(String sessionId, TenantContext tenantContext);

    /**
     * 获取会话上下文
     */
    PipelineContext getSession(String sessionId);

    /**
     * 移除会话（通话结束时清理）
     */
    void remove(String sessionId);
}
```

### 10.2 会话生命周期

```
CallRequest.INIT 到达
        │
        ▼
┌──────────────────────────────┐
│ 创建 PipelineContext          │
│ 反序列化 ScriptSnapshot        │
│ 初始化 DialogManager          │
│ 注册到 SessionManager         │
└────────────┬─────────────────┘
             ▼
┌──────────────────────────────┐
│ 通话进行中                     │
│ 持续接收 AUDIO 请求             │
│ 管道处理 → 返回 CallResponse   │
│ 上下文记忆累积                  │
└────────────┬─────────────────┘
             ▼
CallRequest.CONTROL(STOP) 或流关闭
        │
        ▼
┌──────────────────────────────┐
│ 清理会话资源                   │
│ ASR 关闭识别会话               │
│ TTS 关闭合成会话               │
│ SessionManager.remove()       │
└──────────────────────────────┘
```

### 10.3 虚拟线程与上下文传递

```java
// 每个通话会话的音频处理在独立虚拟线程中执行
Thread.startVirtualThread(() -> {
    // ScopedValue 传递租户上下文（非 ThreadLocal）
    ScopedValue.where(TenantContextHolder.getScopedValue(), 
        context.getTenantContext()).run(() -> {
            // 管道处理，内部任意位置可调用 TenantContextHolder.get()
            pipeline.processAudio(request, context, responseObserver);
    });
});
```

**设计决策**：
- 每个通话会话绑定虚拟线程，I/O 密集（模型推理等待）时自动挂起，不占用载体线程
- 租户上下文用 Scoped Values 传递（AGENTS.md 约束），生命周期受结构化并发约束
- 单节点支持 500+ 并发通话会话（JDK 21 虚拟线程轻量级特性）

---

## 11. 知识库检索

### 11.1 检索时机

当 NLU 判定意图为 `ASK_QUESTION`（用户主动提问）时，DM 在执行话术状态机主流程前，先检索知识库：

```
NLU 结果: intent=ASK_QUESTION
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 从 ScriptSnapshot 获取知识库 ID 列表 │
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 检索知识库                        │
│    @HttpExchange 调用 agent-service   │
│    或本地关键词匹配                   │
│    匹配最相关的 FAQ 条目              │
└────────────┬─────────────────────────┘
             ▼
         匹配到？
         ┌──┴──┐
        是     否
         │      │
         ▼      ▼
┌──────────┐  ┌──────────────────┐
│ 用 FAQ   │  │ 使用 LLM 生成    │
│ 答案回复  │  │ 兜底回复          │
│ 后回到   │  │ 后回到话术主流程    │
│ 话术主流程 │  └──────────────────┘
└──────────┘
```

### 11.2 检索方式

| 方式 | 实现 | 适用场景 |
|------|------|----------|
| 关键词匹配 | `keywords` 字段精确匹配 | 条目数 < 500 |
| 向量检索 | question 向量化 + 语义相似度 | 条目数 ≥ 500 或需模糊匹配 |

**TODO**：向量检索方案（Redis Vector / pgvector / Milvus）和向量化时机（知识库写入时同步向量化 vs 通话时实时向量化）在本模块设计时确定。

---

## 12. 错误码定义（ai-engine 区间 7000-7999）

新增 `AiErrorCode` 枚举，通过 `new BizException(AiErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 7001 | ASR_MODEL_ERROR | ASR 模型调用异常 | ASR 推理服务不可用/超时 |
| 7002 | ASR_SESSION_NOT_FOUND | ASR 会话不存在 | 通话会话未初始化 |
| 7003 | NLU_CLASSIFY_FAILED | 意图分类失败 | 小模型 + LLM 均失败 |
| 7004 | NLU_LLM_TIMEOUT | LLM 推理超时 | LLM 兜底分类超时（> 3s） |
| 7005 | DM_NODE_NOT_FOUND | 话术节点不存在 | 跳转到不存在的节点 |
| 7006 | DM_TRANSITION_NOT_MATCHED | 无匹配的跳转规则 | 当前意图无对应的 Transition |
| 7007 | DM_SCRIPT_INVALID | 话术配置无效 | 快照反序列化失败/结构异常 |
| 7008 | TTS_MODEL_ERROR | TTS 模型调用异常 | TTS 合成服务不可用/超时 |
| 7009 | TTS_VOICE_NOT_FOUND | 音色配置不存在 | Agent 绑定的音色已删除 |
| 7010 | MODEL_ROUTER_ERROR | 模型路由失败 | 无可用模型实例 |
| 7011 | SESSION_EXPIRED | 会话已过期 | 会话超时未活跃（> 30min） |
| 7012 | PIPELINE_ERROR | 管道处理异常 | 管道流程内部错误 |
| 7013 | KNOWLEDGE_SEARCH_FAILED | 知识库检索失败 | agent-service 调用异常 |

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

    <!-- vhuan-call-api：CallProcessApi 接口与 DTO（ai-engine 实现此接口） -->
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-call-api</artifactId>
    </dependency>

    <!-- Spring Web（@HttpExchange 调用 agent-service） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Dubbo（Triple 协议，BIDI STREAM 服务端） -->
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-spring-boot-starter</artifactId>
    </dependency>

    <!-- Nacos 服务注册 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>

    <!-- Sentinel（@HttpExchange 熔断降级 + 模型调用熔断） -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    </dependency>

    <!-- Redis（Redisson，会话状态缓存 + 模型路由缓存） -->
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>

    <!-- Hutool（工具类：DateUtil/StrUtil/JSONUtil） -->
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
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
- 引入 `vhuan-call-api`：实现 `CallProcessApi`（BIDI STREAM 服务端），引用 `CallRequest`/`CallResponse` DTO
- 不依赖 `vhuan-agent` 实现模块，话术配置通过 `CallRequest.INIT` 的 JSON 快照传入，ai-engine 自定义 `ScriptSnapshot` 类反序列化
- 不依赖 MyBatis-Flex / PostgreSQL：ai-engine 不持久化数据，通话过程中的转写、意向、槽位等数据由 call-service 写库
- 不依赖 Kafka：ai-engine 不发布事件，所有输出通过 BIDI STREAM 返回给 call-service
- 不依赖 netty-socketio：ai-engine 不直接推送前端，由 call-service 中转
- 引入 Redis：缓存模型路由配置、会话级临时状态（TODO：评估是否需要 Redis，若会话全部在内存管理可移除）

### 13.2 application.yml 核心配置

```yaml
server:
  port: 8086

spring:
  application:
    name: vhuan-ai-engine
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

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
    name: vhuan-ai-engine
  protocol:
    name: tri
    port: 20886  # Dubbo 端口与 HTTP 端口分离
  registry:
    address: nacos://${NACOS_ADDR:localhost:8848}?namespace=${NACOS_NAMESPACE:vhuan}
  provider:
    timeout: 60000  # BIDI STREAM 长连接超时

# AI 引擎配置
ai-engine:
  # ASR 配置
  asr:
    default-model: sensevoice
    vad-silence-ms: 500          # VAD 静音判定阈值（毫秒）
    silence-timeout-ms: 3000     # 用户沉默超时（毫秒）
    sample-rate: 16000           # 音频采样率

  # NLU 配置
  nlu:
    confidence-threshold: 0.8    # 小模型置信度阈值
    llm-timeout-ms: 3000         # LLM 兜底超时
    llm-model: qwen-plus          # 兜底 LLM 模型

  # DM 配置
  dm:
    max-fallback-retry: 3        # 兜底回复最大重试次数
    max-dialog-history: 20       # 上下文记忆最大轮次

  # TTS 配置
  tts:
    default-model: cosyvoice
    chunk-size: 4096             # 音频分块大小（字节）

  # 模型路由配置
  model-router:
    asr:
      default: sensevoice
      enterprise: whisper-large
    nlu:
      default: small-model
      fallback: qwen-plus
    tts:
      default: cosyvoice

  # 会话超时（分钟）— 30 分钟无活跃自动清理
  session-timeout-minutes: 30

# 远程服务地址
service:
  agent:
    url: http://vhuan-agent
```

---

## 14. 关键流程时序

### 14.1 完整通话 AI 处理时序

```
call-service                      ai-engine
    │                                  │
    │ CallRequest(INIT + snapshot)     │
    │─────────────────────────────────▶│
    │                                  │ 反序列化 ScriptSnapshot
    │                                  │ 创建 PipelineContext
    │                                  │ DialogManager.initialize()
    │                                  │ 当前节点 = GREETING
    │                                  │ 生成开场白文本
    │                                  │ TTS 合成开场白音频
    │◀──CallResponse(TTS, audio)──────│
    │◀──CallResponse(DM, nodeId)──────│
    │                                  │
    │ 播放开场白音频给客户              │
    │                                  │
    │ 客户听到开场白后说话              │
    │ 接收 RTP 音频 → PCM              │
    │                                  │
    │ CallRequest(AUDIO, pcm)         │
    │─────────────────────────────────▶│
    │                                  │ ASR: VAD 检测语音活动
    │                                  │ ASR: 部分转写结果
    │◀──CallResponse(ASR, partial)────│
    │                                  │
    │ 继续推送音频                      │
    │ CallRequest(AUDIO, pcm)         │
    │─────────────────────────────────▶│
    │                                  │ ASR: VAD 检测静音
    │                                  │ ASR: 最终转写结果
    │◀──CallResponse(ASR, final)──────│
    │                                  │
    │                                  │ NLU: 小模型分类
    │                                  │ intent=POSITIVE, conf=0.92
    │◀──CallResponse(NLU, intent)─────│
    │                                  │
    │                                  │ DM: 匹配 Transition
    │                                  │ 跳转到 SLOT_FILLING
    │                                  │ 生成回复 + 变量替换
    │◀──CallResponse(DM, nodeId)──────│
    │◀──CallResponse(SLOT, key)──────│
    │                                  │
    │                                  │ TTS: 流式合成回复
    │◀──CallResponse(TTS, audio)──────│
    │◀──CallResponse(TTS, audio)──────│
    │◀──CallResponse(TTS, last)──────│
    │                                  │
    │ 播放回复音频给客户               │
    │ ... 循环 ...                    │
    │                                  │
    │ CallRequest(CONTROL, STOP)      │
    │─────────────────────────────────▶│
    │                                  │ 清理会话资源
    │                                  │ ASR/TTS 关闭会话
    │◀─────onCompleted()──────────────│
```

### 14.2 坐席打断处理

```
call-service                      ai-engine
    │                                  │
    │ 坐席切入                          │
    │ CallRequest(CONTROL, INTERRUPT)  │
    │─────────────────────────────────▶│
    │                                  │ context.interrupted = true
    │                                  │ TTS: 停止当前合成
    │                                  │ 暂停话术状态机执行
    │◀─────ack────────────────────────│
    │                                  │
    │ （坐席通话期间，call-service      │
    │   不推送音频到 ai-engine）         │
    │                                  │
    │ 坐席切出                          │
    │ CallRequest(CONTROL, RESUME)     │
    │─────────────────────────────────▶│
    │                                  │ context.interrupted = false
    │                                  │ 从当前节点继续执行
    │◀─────ack────────────────────────│
```

---

## 15. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 意图分类策略 | 小模型 vs LLM vs 双层 | **双层（小模型 + LLM 兜底）** | 小模型处理 80% 明确意图（< 200ms），LLM 兜底模糊表述（高精度） |
| ASR 断句方式 | 固定时长 vs VAD 检测 | **VAD 检测** | 固定时长无法适配语速差异，VAD 按实际静音断句更精准 |
| TTS 合成方式 | 整句合成 vs 流式合成 | **流式合成** | 整句合成首音延迟高（> 1s），流式合成首音 < 500ms |
| 话术配置获取 | 每次回查 vs 快照传入 | **快照传入（CallRequest.INIT）** | 通话过程中不回查 agent-service，降低延迟；快照不可变保证一致性 |
| 话术配置反序列化 | 依赖 agent 模块类 vs 自定义类 | **自定义 ScriptSnapshot** | 避免模块依赖，ai-engine 不依赖 vhuan-agent 实现模块 |
| ai-engine 是否持久化 | 写库 vs 纯内存 | **纯内存** | 转写/意向/槽位等数据由 call-service 写库，ai-engine 职责单一 |
| ai-engine 是否直接推前端 | WebSocket vs 通过 call-service 中转 | **通过 call-service 中转** | AGENTS.md 约束"ai-engine 不直接暴露给前端" |
| 知识库检索 | 本地检索 vs 回查 agent-service | **回查 agent-service** | 知识库数据量大，本地缓存成本高；agent-service 已有缓存机制 |
| 模型调用熔断 | 不熔断 vs Sentinel 熔断 | **Sentinel 熔断** | 模型不可用时快速失败返回兜底回复，避免级联故障 |
| 线程模型 | 线程池 vs 虚拟线程 | **虚拟线程** | 500+ 并发通话会话，模型推理等待时自动挂起 |
| NLU 置信度阈值 | 0.7 vs 0.8 vs 0.9 | **0.8（可调）** | 平衡小模型覆盖率和 LLM 兜底频率，实际测试后调优 |
| VAD 静音阈值 | 300ms vs 500ms vs 800ms | **500ms** | 电话场景下 500ms 静音足够判定一句话结束，过短导致断句碎 |

---

## 16. 自检清单

- [ ] Dubbo BIDI STREAM 服务端：实现 `CallProcessApi.processCall()`，接收 CallRequest 返回 CallResponse
- [ ] 请求分发：INIT（初始化）/ AUDIO（音频流）/ CONTROL（控制指令）三种类型正确处理
- [ ] ASR 模块：流式识别 + VAD 断句 + 多模型适配（SenseVoice/Whisper）
- [ ] ASR 回调：部分结果（PARTIAL）+ 最终结果（FINAL）通过 CallResponse(ASR) 返回
- [ ] NLU 模块：双层意图分类（小模型 + LLM 兜底，阈值 0.8）+ 槽位提取 + 情感分析
- [ ] NLU 结果通过 CallResponse(NLU) 返回
- [ ] DM 模块：话术状态机执行（节点跳转 + Transition 匹配 + 动作执行）
- [ ] DM 结果通过 CallResponse(DM) 返回 currentNodeId
- [ ] 变量替换：${变量名} 替换为实际值（槽位 > 联系人 > 全局 > 系统 > 默认）
- [ ] 槽位收集结果通过 CallResponse(SLOT) 返回
- [ ] TTS 模块：流式合成 + 多音色适配（CosyVoice/ChatTTS）+ SSML 支持
- [ ] TTS 音频通过 CallResponse(TTS) 分块返回
- [ ] 模型路由：按租户/场景/成本/负载路由到不同模型实例
- [ ] 会话管理：每个通话独立 PipelineContext，虚拟线程处理，Scoped Values 传上下文
- [ ] 知识库检索：ASK_QUESTION 意图时检索 FAQ，通过 @HttpExchange 调用 agent-service
- [ ] 坐席打断：INTERRUPT 暂停执行，RESUME 从当前节点继续
- [ ] 兜底重试：FALLBACK 循环不超过 3 次
- [ ] 端到端延迟 < 2s（ASR 500ms + NLU 200ms + DM 50ms + TTS 400ms）
- [ ] 不持久化数据（纯内存，数据由 call-service 写库）
- [ ] 不直接推前端（通过 BIDI STREAM 返回，call-service 中转 WebSocket）
- [ ] 不依赖 vhuan-agent 实现模块（话术快照通过 CallRequest.INIT JSON 传入，自定义 ScriptSnapshot 反序列化）
- [ ] 错误码使用 `AiErrorCode`（7000-7999 区间）
- [ ] Sentinel 熔断模型调用和 @HttpExchange
- [ ] 会话超时 30 分钟自动清理
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）详细设计全部完成。**
>
> **下一步**：进入第三阶段旁路支撑模块设计。建议顺序：`vhuan-contact` → `vhuan-analytics` → `vhuan-notification` → `vhuan-sip-connector`。
