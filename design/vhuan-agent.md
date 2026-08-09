# vhuan-agent 详细设计

> **模块**: vhuan-agent（Agent 配置与话术服务）  
> **阶段**: 第二阶段 — 核心业务链路  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供 AI Agent 的配置管理能力，包括 Agent 生命周期管理、话术流程编排（意图 + 状态机模型）、知识库管理、音色管理。是核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）的第三环，为 `campaign-service` 提供可调度的 Agent 实体，为 `ai-engine-service` 提供话术执行所需的配置数据。

**职责边界**：
- Agent 配置：创建/编辑/启停 Agent、绑定音色与知识库、参数调优
- 话术编排：话术节点树 CRUD、意图分支配置、槽位定义、变量管理、节点跳转规则
- 知识库：FAQ 条目管理、分类标签、批量导入导出
- 音色管理：系统音色列表、租户自定义音色、试听
- 话术校验：发布前校验节点树完整性、闭环检测、必填槽位检查

**非职责**：
- 不执行话术运行时逻辑（由 `ai-engine-service` 的对话管理引擎负责），只提供配置数据
- 不直接处理音频流（由 `call-service` 和 `ai-engine-service` 协同处理）
- 不管理外呼任务调度（由 `campaign-service` 负责），只提供 Agent 实体供任务绑定
- 不进行 ASR/TTS 模型调用（由 `ai-engine-service` 负责），只管理音色配置元数据

**与 ai-engine 的分工**：
- **agent-service 管配置**：话术节点树长什么样、意图怎么分、槽位收集什么、音色用哪个
- **ai-engine-service 管执行**：实时识别用户说了什么（ASR）、判断用户意图（NLU）、在话术树里走到哪个节点（DM）、生成回复并合成语音（TTS）
- 两者通过 `@HttpExchange` 在通话开始时传递话术配置快照，通话过程中 ai-engine 不再回查 agent-service

---

## 2. 模块结构

```
vhuan-agent/
├── pom.xml
├── src/main/java/com/vhuan/agent/
│   ├── AgentApplication.java                     # 启动类
│   │
│   ├── controller/
│   │   ├── AgentController.java                   # Agent 配置管理
│   │   ├── ScriptController.java                  # 话术模板管理
│   │   ├── ScriptNodeController.java              # 话术节点编辑
│   │   ├── KnowledgeController.java              # 知识库管理
│   │   └── VoiceController.java                  # 音色管理
│   │
│   ├── service/
│   │   ├── AgentService.java                      # Agent 核心逻辑
│   │   ├── ScriptService.java                    # 话术模板管理
│   │   ├── ScriptNodeService.java                 # 话术节点树操作
│   │   ├── ScriptValidator.java                  # 话术校验引擎
│   │   ├── KnowledgeService.java                  # 知识库管理
│   │   ├── VoiceService.java                     # 音色管理
│   │   └── impl/
│   │       ├── AgentServiceImpl.java
│   │       ├── ScriptServiceImpl.java
│   │       ├── ScriptNodeServiceImpl.java
│   │       ├── ScriptValidatorImpl.java
│   │       ├── KnowledgeServiceImpl.java
│   │       └── VoiceServiceImpl.java
│   │
│   ├── mapper/
│   │   ├── AgentConfigMapper.java
│   │   ├── AgentScriptMapper.java
│   │   ├── AgentScriptNodeMapper.java
│   │   ├── AgentScriptTransitionMapper.java
│   │   ├── AgentScriptSlotMapper.java
│   │   ├── AgentScriptVariableMapper.java
│   │   ├── AgentKnowledgeMapper.java
│   │   └── AgentVoiceMapper.java
│   │
│   ├── entity/
│   │   ├── AgentConfig.java
│   │   ├── AgentScript.java
│   │   ├── AgentScriptNode.java
│   │   ├── AgentScriptTransition.java
│   │   ├── AgentScriptSlot.java
│   │   ├── AgentScriptVariable.java
│   │   ├── AgentKnowledge.java
│   │   └── AgentVoice.java
│   │
│   ├── dto/
│   │   ├── AgentCreateRequest.java
│   │   ├── AgentUpdateRequest.java
│   │   ├── ScriptCreateRequest.java
│   │   ├── ScriptNodeCreateRequest.java
│   │   ├── ScriptTransitionRequest.java
│   │   ├── KnowledgeImportRequest.java
│   │   └── VoiceBindRequest.java
│   │
│   ├── vo/
│   │   ├── AgentVO.java
│   │   ├── AgentDetailVO.java                      # 含话术树完整结构
│   │   ├── ScriptVO.java
│   │   ├── ScriptNodeVO.java
│   │   ├── ScriptTreeVO.java                      # 完整话术树（节点 + 边 + 槽位 + 变量）
│   │   ├── KnowledgeVO.java
│   │   ├── VoiceVO.java
│   │   └── ScriptValidateResultVO.java            # 校验结果
│   │
│   ├── api/                                        # 对外暴露的 @HttpExchange 接口
│   │   ├── AgentApi.java                          # 供 campaign-service / ai-engine 调用
│   │   └── VoiceApi.java                          # 音色查询接口
│   │
│   ├── enums/
│   │   ├── NodeType.java                          # 节点类型枚举
│   │   ├── IntentType.java                        # 意图类型枚举
│   │   ├── ActionType.java                        # 节点动作枚举
│   │   ├── SlotDataType.java                      # 槽位数据类型枚举
│   │   └── AgentStatus.java                       # Agent 状态枚举
│   │
│   └── config/
│       └── AgentProperties.java                    # 服务配置
│
└── src/main/resources/
    └── application.yml
```

**包设计要点**：
- `api/` 包中的 `AgentApi`、`VoiceApi` 是 `@HttpExchange` 接口，供 `campaign-service` 和 `ai-engine-service` 通过 Maven 依赖引用
- `enums/` 包集中管理话术引擎的类型枚举，避免字符串硬编码
- 话术节点树拆分为 `AgentScriptNode`（节点）、`AgentScriptTransition`（边/跳转规则）、`AgentScriptSlot`（槽位）、`AgentScriptVariable`（变量）四张表，比单表 JSON 存储更利于查询与校验

---

## 3. 数据模型设计

所有表落在**租户专属 Schema**（`tenant_{tenantCode}`），由 `vhuan-tenant` 的 SchemaManager 在租户创建时自动初始化。租户间数据物理隔离。

### 3.1 表关系

```
┌──────────────┐     ┌──────────────────┐
│ agent_config  │     │ agent_voice       │
│──────────────│     │──────────────────│
│ id            │     │ id                │
│ agent_name    │     │ voice_code         │
│ agent_code    │     │ voice_name         │
│ script_id     │────▶│ voice_provider     │
│ voice_id      │─────│ voice_config       │
│ knowledge_ids │     │ voice_type         │
│ status        │     │ status             │
│ llm_model     │     └──────────────────┘
│ llm_params    │
│ asr_model     │     ┌──────────────────┐
│ tts_model     │     │ agent_knowledge   │
│ intent_tags   │     │──────────────────│
│ global_vars   │     │ id                │
└──────────────┘     │ script_id          │
                      │ category           │
┌──────────────────┐  │ question           │
│ agent_script      │  │ answer             │
│──────────────────│  │ keywords           │
│ id                │  │ priority           │
│ script_name       │  │ status             │
│ script_code       │  └──────────────────┘
│ version           │
│ status            │  ┌──────────────────┐
│ root_node_id      │  │ agent_script_slot │
│ description       │  │──────────────────│
└────────┬─────────┘  │ id                │
         │             │ node_id            │
         │             │ slot_key           │
┌────────▼─────────┐  │ slot_name          │
│agent_script_node  │  │ data_type          │
│──────────────────│  │ required           │
│ id                │  │ validation_rule    │
│ script_id         │  │ default_value      │
│ node_code         │  └──────────────────┘
│ node_name         │
│ node_type         │  ┌──────────────────────┐
│ intent_type       │  │agent_script_variable │
│ prompt_text       │  │──────────────────────│
│ reply_text        │  │ id                    │
│ action_type       │  │ script_id             │
│ action_params     │  │ var_key               │
│ condition_expr    │  │ var_name              │
│ order_num         │  │ var_source            │
│ status            │  │ default_value         │
└────────┬─────────┘  └──────────────────────┘
         │
         │
┌────────▼─────────────────┐
│agent_script_transition    │
│──────────────────────────│
│ id                        │
│ source_node_id            │
│ target_node_id            │
│ intent_type               │
│ condition_expr            │
│ priority                  │
└──────────────────────────┘
```

### 3.2 AgentConfig

```java
@TableName("agent_config")
public class AgentConfig extends BaseEntity {

    /** Agent 名称 */
    @Column
    private String agentName;

    /** Agent 编码（租户内唯一，供任务绑定时引用） */
    @Column
    private String agentCode;

    /** 绑定的话术模板 ID */
    @Column
    private String scriptId;

    /** 绑定的音色 ID */
    @Column
    private String voiceId;

    /** 绑定的知识库 ID 列表（JSON 数组，如 ["k001","k002"]） */
    @Column
    private String knowledgeIds;

    /** 状态：DRAFT=草稿, PUBLISHED=已发布, ARCHIVED=已归档 */
    @Column
    private String status;

    /** LLM 模型编码（如 qwen-plus / deepseek-v3） */
    @Column
    private String llmModel;

    /** LLM 参数（JSON，如 {"temperature":0.7,"maxTokens":1024}） */
    @Column
    private String llmParams;

    /** ASR 模型编码（如 sensevoice / whisper-large） */
    @Column
    private String asrModel;

    /** TTS 模型编码（如 cosyvoice / chattts） */
    @Column
    private String ttsModel;

    /** 意图标签（JSON 数组，如 ["有意向","犹豫","拒绝","转人工"]） */
    @Column
    private String intentTags;

    /** 全局变量（JSON，如 {"product":"智能POS机","company":"汇智科技"}） */
    @Column
    private String globalVars;

    /** 简介 */
    @Column
    private String description;
}
```

**设计要点**：
- `agentCode` 租户内唯一，用于 campaign 绑定 Agent 时的业务标识
- `knowledgeIds` 存 JSON 数组而非关联表，因为一个 Agent 绑定的知识库数量通常 ≤ 5 个，JSON 查询足够
- `llmParams` 存 JSON，不同模型的参数差异大，用 JSON 更灵活
- `intentTags` 定义该 Agent 支持的意图分类标签，ai-engine 的 NLU 模块根据此标签集做意图分类
- `globalVars` 存储全局变量，话术节点的 `${变量名}` 引用这些值

### 3.3 AgentScript

```java
@TableName("agent_script")
public class AgentScript extends BaseEntity {

    /** 话术模板名称 */
    @Column
    private String scriptName;

    /** 话术编码（租户内唯一） */
    @Column
    private String scriptCode;

    /** 版本号（每次发布自增） */
    @Column
    private Integer version;

    /** 状态：DRAFT=编辑中, PUBLISHED=已发布, OFFLINE=已下线 */
    @Column
    private String status;

    /** 根节点 ID（发布时锁定，指向第一个节点） */
    @Column
    private String rootNodeId;

    /** 话术描述 */
    @Column
    private String description;
}
```

**版本设计**：话术模板支持版本管理。草稿状态可反复编辑节点树，发布时生成新版本并锁定 `rootNodeId`。已发布的版本不可修改，确保进行中的通话使用一致的话术配置。campaign 绑定 Agent 时使用当前已发布版本。

### 3.4 AgentScriptNode

```java
@TableName("agent_script_node")
public class AgentScriptNode extends BaseEntity {

    /** 所属话术模板 ID */
    @Column
    private String scriptId;

    /** 节点编码（话术内唯一） */
    @Column
    private String nodeCode;

    /** 节点名称 */
    @Column
    private String nodeName;

    /** 节点类型（见 NodeType 枚举） */
    @Column
    private String nodeType;

    /** 意图类型（见 IntentType 枚举，GREETING 节点为 null） */
    @Column
    private String intentType;

    /** 提示词（发给 LLM 的 prompt，用于生成动态回复） */
    @Column
    private String promptText;

    /** 固定回复话术（支持变量替换 ${变量名}） */
    @Column
    private String replyText;

    /** 节点动作（见 ActionType 枚举） */
    @Column
    private String actionType;

    /** 动作参数（JSON，如 {"targetNode":"collect_info","intent":"POSITIVE"}） */
    @Column
    private String actionParams;

    /** 进入条件表达式（如 slots.phone != null && intent == "POSITIVE"） */
    @Column
    private String conditionExpr;

    /** 排序号（同层级节点排序） */
    @Column
    private Integer orderNum;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}
```

### 3.5 AgentScriptTransition

```java
@TableName("agent_script_transition")
public class AgentScriptTransition extends BaseEntity {

    /** 源节点 ID */
    @Column
    private String sourceNodeId;

    /** 目标节点 ID */
    @Column
    private String targetNodeId;

    /** 触发意图（如 POSITIVE / HESITANT / NEGATIVE / FALLBACK） */
    @Column
    private String intentType;

    /** 条件表达式（可选，槽位状态等复杂条件） */
    @Column
    private String conditionExpr;

    /** 优先级（数字越小优先级越高，同源节点的多条边按优先级匹配） */
    @Column
    private Integer priority;
}
```

**设计决策**：话术跳转规则独立为 `AgentScriptTransition` 表（边表），而非在节点中存储 `nextNodeId`。原因：一个节点可能根据不同意图跳转到不同节点（如"收集信息"节点，用户回答完整 → 跳"预约回访"，用户回答不完整 → 跳"追问"），一对多关系用边表表达更自然。

### 3.6 AgentScriptSlot

```java
@TableName("agent_script_slot")
public class AgentScriptSlot extends BaseEntity {

    /** 所属节点 ID */
    @Column
    private String nodeId;

    /** 槽位标识（如 phone / name / appointment_time） */
    @Column
    private String slotKey;

    /** 槽位名称（中文展示） */
    @Column
    private String slotName;

    /** 数据类型（见 SlotDataType 枚举） */
    @Column
    private String dataType;

    /** 是否必填 */
    @Column
    private Boolean required;

    /** 校验规则（正则或枚举，如 ^1\d{10}$ 或 ENUM:morning,afternoon） */
    @Column
    private String validationRule;

    /** 默认值（用户未提供时使用） */
    @Column
    private String defaultValue;
}
```

### 3.7 AgentScriptVariable

```java
@TableName("agent_script_variable")
public class AgentScriptVariable extends BaseEntity {

    /** 所属话术模板 ID */
    @Column
    private String scriptId;

    /** 变量标识（如 customer_name / product_name） */
    @Column
    private String varKey;

    /** 变量名称（中文展示） */
    @Column
    private String varName;

    /** 变量来源：GLOBAL=全局变量, CONTACT=联系人字段, SLOT=槽位收集, SYSTEM=系统变量 */
    @Column
    private String varSource;

    /** 默认值 */
    @Column
    private String defaultValue;
}
```

**变量来源说明**：
- `GLOBAL`：Agent 配置中的 `globalVars`，如产品名称、公司名称
- `CONTACT`：联系人表的字段，如客户姓名、手机号（campaign 传入）
- `SLOT`：槽位收集的值，如预约时间、意向等级
- `SYSTEM`：系统变量，如当前日期、当前时间、坐席工号

### 3.8 AgentKnowledge

```java
@TableName("agent_knowledge")
public class AgentKnowledge extends BaseEntity {

    /** 关联的话术模板 ID（可选，null 表示通用知识库） */
    @Column
    private String scriptId;

    /** 分类（如 产品FAQ / 价格 / 售后） */
    @Column
    private String category;

    /** 问题 */
    @Column
    private String question;

    /** 标准答案（支持变量替换） */
    @Column
    private String answer;

    /** 关键词（逗号分隔，用于匹配检索） */
    @Column
    private String keywords;

    /** 优先级（数字越大优先级越高） */
    @Column
    private Integer priority;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}
```

### 3.9 AgentVoice

```java
@TableName("agent_voice")
public class AgentVoice extends BaseEntity {

    /** 音色编码 */
    @Column
    private String voiceCode;

    /** 音色名称 */
    @Column
    private String voiceName;

    /** 音色类型：SYSTEM=系统内置, CUSTOM=租户自定义 */
    @Column
    private String voiceType;

    /** TTS 服务商（如 cosyvoice / chattts / azure） */
    @Column
    private String voiceProvider;

    /** 音色配置（JSON，如 {"voiceId":"zh-CN-XiaoxiaoNeural","speed":1.0,"pitch":0}） */
    @Column
    private String voiceConfig;

    /** 试听音频 URL */
    @Column
    private String sampleUrl;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}
```

**设计决策**：音色表落在租户 Schema 而非共享 Schema。系统内置音色通过数据初始化脚本预置到每个租户 Schema，租户自定义音色独立管理。这样租户音色查询不需要跨 Schema JOIN。

---

## 4. 话术引擎模型

### 4.1 节点类型（NodeType）

```java
public enum NodeType {
    GREETING,        // 开场白（话术入口，固定播报）
    INTENT_CLASSIFY, // 意图分类（等待用户回复，NLU 判断意图后跳转）
    SLOT_FILLING,    // 槽位收集（收集特定信息，如手机号、预约时间）
    REPLY,           // 固定回复（播报话术后跳转下一节点）
    CONDITION,       // 条件分支（根据槽位状态选择路径）
    ACTION,          // 动作执行（挂断/转人工/标记意向/发短信等）
    ENDING           // 结束节点（通话终止）
}
```

### 4.2 意图类型（IntentType）

```java
public enum IntentType {
    POSITIVE,        // 有意向（积极回应）
    HESITANT,        // 犹豫（需要进一步引导）
    NEGATIVE,        // 拒绝（明确不需要）
    ASK_QUESTION,    // 提问（用户主动询问产品信息）
    CONFIRM,         // 确认（槽位信息确认）
    FALLBACK,        // 兜底（NLU 无法识别意图时的默认分支）
    SILENCE          // 沉默（用户长时间未回应）
}
```

### 4.3 节点动作（ActionType）

```java
public enum ActionType {
    NONE,              // 无动作（仅播报后跳转）
    JUMP,              // 跳转指定节点
    COLLECT_SLOT,      // 收集槽位
    HANGUP,            // 挂断通话
    TRANSFER_HUMAN,    // 转人工坐席
    TAG_INTENT,        // 标记意向等级（A/B/C/D 类）
    SEND_SMS,          // 发送短信
    SET_VARIABLE,      // 设置变量值
    CALL_WEBHOOK       // 调用外部 Webhook
}
```

### 4.4 槽位数据类型（SlotDataType）

```java
public enum SlotDataType {
    STRING,        // 字符串
    PHONE,         // 手机号（校验 ^1\d{10}$）
    DATE,          // 日期（yyyy-MM-dd）
    TIME,          // 时间（HH:mm）
    DATETIME,      // 日期时间
    NUMBER,        // 数字
    ENUM,          // 枚举值
    EMAIL          // 邮箱
}
```

### 4.5 话术树示例

以"POS 机推广"话术为例：

```
节点 GREETING[开场白]
  reply: "您好，我是汇智科技的AI助手，想给您介绍一款智能POS机..."
  action: JUMP → INTENT_1
  │
  ▼
节点 INTENT_CLASSIFY[意向判断]
  prompt: "请问您目前有更换POS机的需求吗？"
  等待用户回复 → NLU 分类意图
  │
  ├── intent=POSITIVE ──▶ 节点 SLOT_1[收集联系方式]
  │                         slots: [phone(必填), name]
  │                         action: COLLECT_SLOT
  │                         │
  │                         ▼ 完成收集
  │                       节点 REPLY[确认信息]
  │                         reply: "好的${name}先生/女士，我帮您记录的号码是${phone}..."
  │                         action: TAG_INTENT(A类) → JUMP → ENDING_BOOK
  │
  ├── intent=HESITANT ──▶ 节点 REPLY[利益点重申]
  │                         reply: "我们的POS机费率低至0.38%，还送扫码枪..."
  │                         action: JUMP → INTENT_1（回到意向判断）
  │
  ├── intent=NEGATIVE ──▶ 节点 ENDING[礼貌结束]
  │                         reply: "好的，打扰您了，祝您生意兴隆。"
  │                         action: TAG_INTENT(D类) → HANGUP
  │
  └── intent=FALLBACK ──▶ 节点 REPLY[兜底回复]
                            reply: "不好意思我没听清，请问您方便了解一下吗？"
                            action: JUMP → INTENT_1（最多重试 3 次 → ENDING）
```

---

## 5. 话术编排流程

### 5.1 创建话术模板

```
租户管理员创建话术模板
        │
        ▼
┌──────────────────────────────┐
│ 1. 创建 AgentScript 记录      │  status=DRAFT, version=0
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 2. 可视化编辑器编辑节点树       │  前端拖拽节点 → 逐个创建 Node
│    （逐节点 API 调用）         │  创建 Transition（边）
│                              │  创建 Slot（槽位）
│                              │  创建 Variable（变量）
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 3. 点击"发布"                 │  触发话术校验
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 4. 话术校验                   │  校验规则见 5.3 节
│    校验通过 → 发布             │  version + 1, status=PUBLISHED
│    校验失败 → 返回错误详情      │  锁定 rootNodeId
└──────────────────────────────┘
```

### 5.2 节点编辑操作

| 操作 | 接口 | 说明 |
|------|------|------|
| 创建节点 | POST `/api/script/{scriptId}/node` | 指定节点类型、名称、回复话术 |
| 更新节点 | PUT `/api/script/node/{nodeId}` | 修改回复话术、提示词、动作 |
| 删除节点 | DELETE `/api/script/node/{nodeId}` | 校验无后续边才可删除 |
| 创建边 | POST `/api/script/node/{nodeId}/transition` | 源节点 → 目标节点，指定意图 |
| 删除边 | DELETE `/api/script/transition/{transitionId}` | 删除跳转规则 |
| 设置槽位 | POST `/api/script/node/{nodeId}/slot` | 为槽位收集节点定义槽位 |
| 设置变量 | POST `/api/script/{scriptId}/variable` | 定义话术变量 |

### 5.3 话术校验规则

```java
public interface ScriptValidator {

    /**
     * 校验话术节点树完整性
     * @return 校验结果（通过返回空列表，失败返回错误详情）
     */
    ScriptValidateResultVO validate(String scriptId);
}
```

校验项：

| 校验项 | 规则 | 失败提示 |
|--------|------|----------|
| 根节点存在 | 话术树必须有一个 GREETING 类型节点作为入口 | 话术缺少开场白节点 |
| 节点闭环检测 | 从根节点出发 DFS 遍历，所有路径最终到达 ENDING 节点 | 节点"收集信息"无终止路径，存在死循环 |
| 孤立节点检测 | 所有非根节点必须至少有一条入边 | 节点"预约回访"无入口路径，无法到达 |
| 必填槽位检查 | SLOT_FILLING 节点至少定义一个 required=true 的槽位 | 槽位收集节点"收集联系方式"未定义必填槽位 |
| 跳转目标存在 | Transition 的 targetNodeId 指向的节点存在且 status=1 | 跳转目标节点不存在或已禁用 |
| 意图覆盖检查 | INTENT_CLASSIFY 节点至少有 POSITIVE + NEGATIVE + FALLBACK 三条出边 | 意图判断节点缺少兜底分支 |
| 变量引用检查 | replyText 中的 ${变量名} 在 Variable 表中已定义 | 回复话术引用了未定义的变量 ${product_name} |
| 兜底重试限制 | FALLBACK 分支形成的循环不超过 3 次 | 兜底回复循环超过 3 次，可能导致无限重试 |

---

## 6. 话术配置快照机制

### 6.1 为什么需要快照

通话过程中 ai-engine 需要完整的话术配置（节点树 + 边 + 槽位 + 变量）。如果每次节点跳转都通过 `@HttpExchange` 回查 agent-service，会产生大量同步调用，增加通话延迟。

**方案**：通话开始时，call-service 一次性拉取话术完整配置（快照），通过 Dubbo Triple 的 BIDIRECTIONAL_STREAM 传递给 ai-engine。通话过程中 ai-engine 在本地执行话术状态机，不再回查 agent-service。

### 6.2 快照数据结构

```java
/**
 * 话术配置快照 — 通话开始时由 ai-engine 通过 AgentApi 拉取
 * 包含完整的话术树结构，通话过程中不再回查
 */
public record ScriptSnapshot(
    String scriptId,               // 话术模板 ID
    Integer version,                // 话术版本号
    String rootNodeId,             // 根节点 ID
    List<NodeSnapshot> nodes,      // 全部节点
    List<TransitionSnapshot> transitions,  // 全部边
    List<SlotSnapshot> slots,      // 全部槽位定义
    List<VariableSnapshot> variables,      // 全部变量定义
    Map<String, String> globalVars,       // 全局变量键值对
    List<String> intentTags,              // 意图标签集合
    String llmModel,                       // LLM 模型编码
    String llmParams                      // LLM 参数
) {}

public record NodeSnapshot(
    String nodeId,
    String nodeCode,
    String nodeType,
    String intentType,
    String promptText,
    String replyText,
    String actionType,
    String actionParams,
    String conditionExpr
) {}

public record TransitionSnapshot(
    String sourceNodeId,
    String targetNodeId,
    String intentType,
    String conditionExpr,
    Integer priority
) {}
```

**设计决策**：使用 JDK 21 Record 定义快照数据结构，不可变、线程安全，适合在 Dubbo Triple 流中序列化传输。

### 6.3 快照拉取接口

```java
@HttpExchange(url = "${service.agent.url}", name = "agentApi")
public interface AgentApi {

    /**
     * 获取 Agent 完整配置快照（含话术树）
     * 通话开始时由 call-service 或 ai-engine 调用
     */
    @GetExchange("/api/internal/agent/{agentCode}/snapshot")
    AgentDetailVO getAgentSnapshot(@PathVariable String agentCode);

    /**
     * 获取话术完整配置（节点树 + 边 + 槽位 + 变量）
     */
    @GetExchange("/api/internal/script/{scriptId}/tree")
    ScriptTreeVO getScriptTree(@PathVariable String scriptId);

    /**
     * 获取音色配置
     */
    @GetExchange("/api/internal/voice/{voiceId}")
    VoiceVO getVoice(@PathVariable String voiceId);
}
```

---

## 7. 知识库管理

### 7.1 知识库定位

知识库（FAQ）是对话过程中的**辅助知识源**，不参与话术状态机的主流程。当用户提问超出话术预设路径时，ai-engine 的对话管理模块检索知识库匹配最相关的 Q&A，补充回复。

```
话术主流程：GREETING → INTENT_CLASSIFY → SLOT_FILLING → ENDING
                                         │
                                         │ 用户突然问"你们POS机有指纹支付吗？"
                                         ▼
                                    ai-engine 检索知识库
                                    匹配 FAQ → 回答 → 回到主流程
```

### 7.2 知识库接口

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 知识库列表 | GET | `/api/knowledge/list` | `knowledge:list` | 分页，支持按分类筛选 |
| 创建条目 | POST | `/api/knowledge` | `knowledge:create` | 单条创建 |
| 更新条目 | PUT | `/api/knowledge/{id}` | `knowledge:update` | |
| 删除条目 | DELETE | `/api/knowledge/{id}` | `knowledge:delete` | |
| 批量导入 | POST | `/api/knowledge/import` | `knowledge:import` | Excel/CSV 导入 |
| 导出 | GET | `/api/knowledge/export` | `knowledge:list` | 导出当前筛选结果 |
| 批量启用/禁用 | PUT | `/api/knowledge/batch-status` | `knowledge:update` | |

### 7.3 检索策略

知识库检索由 ai-engine 负责，agent-service 只管理数据：

| 检索方式 | 说明 | 适用场景 |
|----------|------|----------|
| 关键词匹配 | `keywords` 字段精确匹配 | 条目数 < 500 的小型知识库 |
| 向量检索（可选） | 将 question 向量化存储，语义相似度匹配 | 条目数 > 500 或需要模糊匹配 |

**TODO**：向量检索是否启用取决于 ai-engine 的 LLM 方案。如果使用 RAG（检索增强生成），需在知识库写入时同步向量化。向量存储方案（Redis Vector / Milvus / PostgreSQL pgvector）在 ai-engine 模块设计时确定。

---

## 8. 音色管理

### 8.1 系统内置音色

| 音色编码 | 名称 | 服务商 | 配置 | 适用场景 |
|----------|------|--------|------|----------|
| `zh-female-1` | 晓晓 | CosyVoice | {"voiceId":"xiaoxiao","speed":1.0} | 标准女声，通用 |
| `zh-male-1` | 云扬 | CosyVoice | {"voiceId":"yunyang","speed":1.0} | 标准男声，通用 |
| `zh-female-2` | 晓伊 | Azure | {"voiceId":"zh-CN-XiaoyiNeural"} | 温柔女声 |
| `zh-male-2` | 云健 | Azure | {"voiceId":"zh-CN-YunjianNeural"} | 浑厚男声 |

系统内置音色在租户 Schema 创建时通过初始化脚本预置，所有租户共享相同的内置音色列表。

### 8.2 租户自定义音色

租户管理员可上传自定义音色样本（如录制销售人员的声音），由 TTS 服务商训练后生成专属音色。自定义音色的 `voiceType=CUSTOM`。

### 8.3 音色接口

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 音色列表 | GET | `/api/voice/list` | `voice:list` | 系统内置 + 租户自定义 |
| 试听 | GET | `/api/voice/{id}/sample` | `voice:list` | 返回试听音频 URL |
| 创建自定义音色 | POST | `/api/voice` | `voice:create` | 上传样本，发起训练 |
| 更新音色配置 | PUT | `/api/voice/{id}` | `voice:update` | 调整语速、音调 |
| 删除自定义音色 | DELETE | `/api/voice/{id}` | `voice:delete` | 仅可删除自定义音色 |

---

## 9. 对外接口设计

### 9.1 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| Agent 列表 | GET | `/api/agent/list` | `agent:list` | 分页查询 |
| Agent 详情 | GET | `/api/agent/{id}` | `agent:list` | 含话术树、音色、知识库 |
| 创建 Agent | POST | `/api/agent` | `agent:create` | |
| 更新 Agent | PUT | `/api/agent/{id}` | `agent:update` | |
| 启用/停用 | PUT | `/api/agent/{id}/status` | `agent:update` | |
| 复制 Agent | POST | `/api/agent/{id}/copy` | `agent:create` | 复制配置创建新 Agent |
| 话术模板列表 | GET | `/api/script/list` | `script:list` | |
| 创建话术 | POST | `/api/script` | `script:create` | |
| 话术详情 | GET | `/api/script/{id}` | `script:list` | 含节点树 |
| 发布话术 | POST | `/api/script/{id}/publish` | `script:publish` | 触发校验 → 发布 |
| 话术校验 | POST | `/api/script/{id}/validate` | `script:list` | 校验不发布 |
| 复制话术 | POST | `/api/script/{id}/copy` | `script:create` | |
| 节点操作 | CRUD | `/api/script/{scriptId}/node/**` | `script:update` | 见 5.2 节 |

### 9.2 权限编码

| 权限编码 | 名称 | 默认角色 |
|----------|------|----------|
| `agent:list` | 查看 Agent | TENANT_ADMIN, SUPERVISOR |
| `agent:create` | 创建 Agent | TENANT_ADMIN |
| `agent:update` | 编辑 Agent | TENANT_ADMIN |
| `script:list` | 查看话术 | TENANT_ADMIN, SUPERVISOR |
| `script:create` | 创建话术 | TENANT_ADMIN |
| `script:update` | 编辑话术 | TENANT_ADMIN |
| `script:publish` | 发布话术 | TENANT_ADMIN |
| `knowledge:list` | 查看知识库 | TENANT_ADMIN, SUPERVISOR |
| `knowledge:create` | 创建知识条目 | TENANT_ADMIN |
| `knowledge:import` | 导入知识库 | TENANT_ADMIN |
| `voice:list` | 查看音色 | TENANT_ADMIN |
| `voice:create` | 创建自定义音色 | TENANT_ADMIN |

---

## 10. 错误码定义（agent 区间 4000-4999）

新增 `AgentErrorCode` 枚举，用法与 `BizErrorCode` 一致，通过 `new BizException(AgentErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 4001 | AGENT_NOT_FOUND | Agent 不存在 | 查询的 Agent ID/Code 不存在 |
| 4002 | AGENT_CODE_DUPLICATE | Agent 编码已存在 | 创建时编码重复 |
| 4003 | AGENT_NOT_PUBLISHED | Agent 未发布 | 绑定到任务时 Agent 状态非 PUBLISHED |
| 4004 | AGENT_ARCHIVED | Agent 已归档 | 操作已归档的 Agent |
| 4005 | SCRIPT_NOT_FOUND | 话术模板不存在 | |
| 4006 | SCRIPT_CODE_DUPLICATE | 话术编码已存在 | |
| 4007 | SCRIPT_NOT_PUBLISHED | 话术未发布 | Agent 绑定未发布的话术 |
| 4008 | SCRIPT_VALIDATE_FAILED | 话术校验失败 | 发布前校验不通过（附带详细错误列表） |
| 4009 | SCRIPT_HAS_ACTIVE_AGENT | 话术已被 Agent 引用 | 删除/下线被引用的话术 |
| 4010 | NODE_NOT_FOUND | 节点不存在 | |
| 4011 | NODE_HAS_TRANSITIONS | 节点存在后续边 | 删除有出边的节点 |
| 4012 | ROOT_NODE_EXISTS | 根节点已存在 | 一个话术只能有一个 GREETING 节点 |
| 4013 | SLOT_REQUIRED_MISSING | 必填槽位缺失 | SLOT_FILLING 节点未定义必填槽位 |
| 4014 | VARIABLE_NOT_DEFINED | 变量未定义 | replyText 引用了未定义的变量 |
| 4015 | KNOWLEDGE_NOT_FOUND | 知识条目不存在 | |
| 4016 | VOICE_NOT_FOUND | 音色不存在 | |
| 4017 | VOICE_SYSTEM_DELETE_FORBIDDEN | 系统音色不可删除 | 删除 voiceType=SYSTEM 的音色 |
| 4018 | TRANSITION_TARGET_INVALID | 跳转目标节点无效 | 目标节点不存在或已禁用 |
| 4019 | SCRIPT_VERSION_LOCKED | 已发布版本不可修改 | 编辑 PUBLISHED 状态的话术节点 |

---

## 11. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `agent:published:{tenantId}:{agentCode}` | String(JSON) | 30min | 已发布 Agent 配置缓存（含话术快照） |
| `script:tree:{scriptId}:{version}` | String(JSON) | 30min | 话术完整树结构缓存（按版本缓存） |
| `voice:list:{tenantId}` | String(JSON) | 60min | 租户音色列表缓存 |

**缓存策略**：
- 话术树按 `scriptId + version` 缓存，已发布版本不可变，缓存命中率高
- Agent 发布/话术发布时主动刷新缓存
- 通话拉取快照时先查 Redis，未命中再查库并写入缓存（30min 过期，通话通常在 30min 内结束）

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
- 不依赖 `vhuan-tenant` 模块，租户上下文通过 `TenantContextHolder` 从请求链路获取
- 数据操作在租户 Schema 内，由 MyBatis-Flex 多租户插件自动切换 `search_path`
- 不引入 Kafka（agent-service 不消费通话事件，也不发布事件）
- 不引入 Dubbo（agent-service 不参与流式通信，仅通过 @HttpExchange 提供配置查询）

### 12.2 application.yml 核心配置

```yaml
server:
  port: 8083

spring:
  application:
    name: vhuan-agent
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

# Agent 服务配置
agent:
  # 话术树缓存过期时间（分钟）
  cache-ttl-minutes: 30
  # 兜底重试最大次数
  fallback-max-retry: 3
  # 知识库批量导入单次最大条数
  knowledge-import-batch-size: 500
```

---

## 13. 关键流程时序

### 13.1 通话开始时拉取话术配置

```
call-service                    agent-service                 ai-engine
    │                                │                           │
    │  通话接通，AI 接管              │                           │
    │──getAgentSnapshot(agentCode)──▶│                           │
    │                                │  查 Redis 缓存             │
    │                                │  未命中 → 查库组装快照      │
    │                                │  写入缓存(30min)           │
    │◀─────AgentDetailVO────────────│                           │
    │                                │                           │
    │  通过 Dubbo Triple BIDI 流     │                           │
    │  传递 ScriptSnapshot──────────────────────────────────────▶│
    │                                │                           │
    │                                │  ai-engine 在本地执行      │
    │                                │  话术状态机，不再回查       │
    │                                │                           │
    │  通话过程中节点跳转、槽位收集    │                           │
    │  均在 ai-engine 本地完成         │                           │
    │  ◀──────────Dubbo BIDI 流──────────────────────────────────│
```

### 13.2 话术发布流程

```
租户管理员点击"发布"
        │
        ▼
┌──────────────────────────────┐
│ 1. 校验话术当前状态            │  status=DRAFT 才可发布
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 2. 执行 ScriptValidator       │  8 项校验规则
│    校验节点树完整性             │  失败 → 返回 ScriptValidateResultVO
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 3. 锁定 rootNodeId            │  确定 GREETING 节点为入口
│    version + 1                │
│    status = PUBLISHED          │
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 4. 刷新 Redis 缓存             │  按 scriptId + version 缓存
└──────────────────────────────┘
```

### 13.3 Agent 绑定话术校验

```
campaign-service 创建任务时绑定 Agent
        │
        ▼
┌──────────────────────────────┐
│ 1. 查询 AgentConfig            │  获取 agentCode
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 2. 校验 Agent 状态              │  status != PUBLISHED → AGENT_NOT_PUBLISHED
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 3. 校验话术状态                │  script.status != PUBLISHED → SCRIPT_NOT_PUBLISHED
│    （通过 AgentApi 查询）       │
└────────────┬────────────────┘
             ▼
┌──────────────────────────────┐
│ 4. 校验音色                    │  voice.status == 1
└────────────┬────────────────┘
             ▼
       校验通过，允许创建任务
```

---

## 14. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 话术跳转规则存储 | 节点内 nextNodeId vs 独立边表 | **独立边表（Transition）** | 一个节点根据不同意图跳转不同节点，一对多关系用边表表达更自然 |
| 话术树存储方式 | JSON 字段 vs 关系表拆分 | **关系表拆分** | 利于单节点查询、增量编辑、校验逻辑实现；JSON 方式校验需反序列化整棵树 |
| 话术配置传递 | 每次节点跳转回查 vs 快照一次拉取 | **快照一次拉取** | 避免通话过程中高频同步调用，降低延迟；快照不可变保证一致性 |
| 快照数据结构 | POJO vs Record | **JDK 21 Record** | 不可变、线程安全，适合序列化传输，代码简洁 |
| 知识库存储 | 关系表 vs 向量库 | **关系表为主，向量库可选** | 条目数 < 500 时关键词匹配足够；向量化方案在 ai-engine 设计时确定 |
| 音色表位置 | 共享 Schema vs 租户 Schema | **租户 Schema** | 避免跨 Schema JOIN，系统音色通过初始化脚本预置 |
| 话术版本管理 | 覆盖更新 vs 版本号递增 | **版本号递增** | 已发布版本锁定不可改，确保进行中通话使用一致配置 |
| knowledgeIds 存储 | 关联表 vs JSON 字段 | **JSON 字段** | 绑定数量通常 ≤ 5，JSON 查询足够，避免多一张关联表 |
| llmParams 存储 | 独立字段 vs JSON | **JSON** | 不同模型参数差异大，JSON 更灵活 |
| 向量检索方案 | 自建 vs 复用 Redis Vector/pgvector | **待 ai-engine 确认** | 向量检索是 ai-engine 的职责，agent-service 只管数据 |

---

## 15. 自检清单

- [ ] Agent 配置：创建/编辑/启停/复制，绑定话术 + 音色 + 知识库
- [ ] 话术编排：节点树 CRUD（7 种节点类型）+ 边（跳转规则）+ 槽位 + 变量
- [ ] 话术校验：8 项校验规则（根节点/闭环/孤立/槽位/跳转/意图覆盖/变量/重试限制）
- [ ] 话术发布：校验通过 → version + 1 → status=PUBLISHED → 锁定 rootNodeId
- [ ] 已发布版本不可修改（SCRIPT_VERSION_LOCKED）
- [ ] 话术快照机制：通话开始一次性拉取完整配置，通过 Dubbo 传递给 ai-engine
- [ ] 快照使用 JDK 21 Record 定义，不可变
- [ ] 知识库：FAQ CRUD + 批量导入导出 + 分类管理
- [ ] 音色：系统内置 + 租户自定义，试听，系统音色不可删除
- [ ] 对外暴露 `AgentApi`、`VoiceApi`（@HttpExchange），供 campaign/ai-engine 调用
- [ ] 错误码使用 `AgentErrorCode`（4000-4999 区间），通过 `BizException` 抛出
- [ ] 数据表落在租户 Schema（`tenant_{tenantCode}`），由 SchemaManager 初始化
- [ ] 不依赖 `vhuan-tenant` 模块，租户上下文从请求链路获取
- [ ] 不引入 Kafka / Dubbo（纯 @HttpExchange 配置查询服务）
- [ ] Redis 缓存话术树（按 scriptId + version，30min 过期）
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，进入 `vhuan-campaign` 详细设计。
