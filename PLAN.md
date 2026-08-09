# 多租户 AI Agent 电话营销系统 — 设计与编码计划

> **版本**: v2.0.0  
> **日期**: 2026-08-09  
> **状态**: 详细设计阶段已完成，进入编码实现阶段，每个模块完成后更新本文档

---

## 推进策略

**设计阶段**（已完成）采用"先打地基，再走核心链路，最后补旁路"三阶段推进：

- **第一阶段**：基础设施层 — 所有模块的编译依赖，必须先稳定
- **第二阶段**：核心业务链路 — 系统的价值命脉，自顶向下逐层打通
- **第三阶段**：旁路支撑 — 核心链路清楚后，接口契约明确，收尾效率高

**编码阶段**（当前）采用**自底向上**推进，与设计阶段方向互补：

- 设计自顶向下：理清需求边界、接口契约、依赖关系
- 编码自底向上：满足编译依赖，逐层编译通过、逐层可单测

---

# 第一部分：详细设计计划

## 第一阶段：基础设施

> 目标：稳定所有模块的编译依赖，为后续服务的详细设计提供统一基础

### 1.1 vhuan-common（公共模块）

**状态**: 设计已完成

**设计文档**: `design/vhuan-common.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 统一响应体 | 复用 Graceful Response 自动包装，`BizException` 继承 `GracefulResponseException` |
| 异常体系 | 业务异常枚举 `BizErrorCode`（按模块区间划分错误码） |
| 租户上下文 | `TenantContext`（Record）+ `TenantContextHolder`（Scoped Values 实现） |
| 基础实体 | 审计字段基类 `BaseEntity`、视图基类 `BaseVO` |
| 常量 | `HeaderConstants`、`SystemConstants` |
| 自动配置 | Hutool `Snowflake` Bean、Jackson 配置 |

**依赖**: 无

---

### 1.2 vhuan-gateway（API 网关）

**状态**: 设计已完成

**设计文档**: `design/vhuan-gateway.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 路由配置 | YAML + Nacos 动态刷新 |
| 全局过滤器 | TraceId → 请求日志 → CORS → JWT 认证 → 租户注入 |
| 限流策略 | Sentinel 四维度（全局 / 租户套餐分档 / 接口级 / IP 级） |
| 鉴权配置 | 白名单（登录/健康检查）vs 需认证路由 |

**依赖**: `vhuan-common`，WebFlux（Netty）模式

---

> **说明**：RPC 层采用 **Apache Dubbo 3 + Triple 协议**。服务契约使用纯 Java 接口定义（`@DubboService` / `@DubboReference`），DTO 定义在被调用方服务模块内由调用方引用。Triple 协议基于 HTTP/2，支持全双工流式（BIDIRECTIONAL_STREAM / CLIENT_STREAM / SERVER_STREAM），注册中心复用 Nacos。已移除独立 `vhuan-proto` 模块，无需 `.proto` IDL。

## 第二阶段：核心业务链路

> 目标：自顶向下打通 `auth → tenant → agent → campaign → call → ai-engine` 完整链路

### 2.1 vhuan-auth（认证授权服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-auth.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 认证接口 | 登录（账号密码 / 手机验证码）、登出、Token 刷新、强制改密 |
| JWT 管理 | Access Token（30min 无状态）+ Refresh Token（7d Redis 存储，登录时旋转） |
| RBAC 权限 | 四级角色（平台管理员 / 租户管理员 / 主管 / 坐席）、权限树、`@RequirePermission` 注解 AOP |
| 租户 API Key | 生成、校验（HMAC 签名防重放）、吊销 |
| 登录安全 | BCrypt 加密、登录失败 5 次锁定 15min |
| 数据表 | `sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`tenant_api_key` |

**依赖**: `vhuan-common`、`vhuan-notification`（短信验证码，@HttpExchange 远程）、Nacos、Redis（Redisson）

---

### 2.2 vhuan-tenant（租户管理服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-tenant.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 租户管理 | 创建租户（自动创建 Schema）、启用/禁用、信息维护 |
| 套餐管理 | 套餐定义、配额模型（并发通道数、存储容量、API 频率） |
| 用量计费 | 并发通话时长累计、存储用量统计、账单生成 |
| Schema 管理 | 租户 Schema 创建/迁移脚本（自建 SchemaManager） |
| 数据表 | `tenant_info`、`tenant_plan`、`tenant_plan_quota`、`tenant_bill` |

**依赖**: `vhuan-common`、`vhuan-auth`（管理员初始化，@HttpExchange 远程）

---

### 2.3 vhuan-agent（Agent 配置与话术服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-agent.md`

**产出**：

| 产出 | 内容 |
|------|------|
| Agent 配置 | 创建/编辑 Agent、绑定音色、绑定知识库、启用/停用 |
| 话术模板 | 话术节点树 CRUD（节点/边/槽位/变量四表）、意图分支、话术校验 |
| 知识库 | FAQ 条目管理、批量导入导出、分类管理 |
| 音色管理 | 系统内置 + 租户自定义音色、试听 |
| 数据表 | `agent_config`、`agent_script`、`agent_script_node`、`agent_script_transition`、`agent_script_slot`、`agent_script_variable`、`agent_knowledge`、`agent_voice` |

**依赖**: `vhuan-common`、`vhuan-tenant`（租户 Schema 初始化，远程）

---

### 2.4 vhuan-campaign（外呼任务调度服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-campaign.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 任务管理 | 创建任务（选择 Agent + 话术 + 名单）、启停、暂停/恢复 |
| 批次管理 | 名单分批、号码分配策略（顺序/随机/权重） |
| 调度策略 | 立即 / 定时 / 预览式外呼，虚拟线程调度引擎 |
| 号码下发 | 通过 Dubbo Triple CLIENT_STREAM 向 `call-service` 批量下发号码 |
| 重试策略 | 未接通号码的重试次数、间隔、时段限制 |
| 数据表 | `campaign`、`campaign_batch`、`campaign_detail`、`campaign_strategy` |

**依赖**: `vhuan-common`、`vhuan-agent`、`vhuan-contact`、`vhuan-call-api`

---

### 2.5 vhuan-call（通话管理服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-call.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 通话状态机 | Idle → Dialing → Ringing → Answered → InProgress → Ending → Ended |
| SIP 信令对接 | 通过 `@HttpExchange` 调用 sip-connector 发起/挂断 |
| 媒体流管理 | 音频流接收/转发、与 ai-engine 的 Dubbo BIDI STREAM 交互 |
| 坐席介入 | 监听（WebSocket 订阅转写流）、切入（媒体流切换）、切出（恢复 AI） |
| 录音管理 | 混音写入临时文件、结束后上传 MinIO |
| 并发控制 | 调用 tenant-service 的 QuotaApi 预扣减/释放通道 |
| 模块拆分 | `vhuan-call-api`（Dubbo 接口 + DTO）+ `vhuan-call`（实现） |
| 数据表 | `call_session`、`call_recording`、`call_transcript`、`call_intent_result`、`call_slot` |

**依赖**: `vhuan-common`、`vhuan-call-api`、`vhuan-tenant`、`vhuan-sip-connector`（@HttpExchange）

---

### 2.6 vhuan-ai-engine（AI 引擎服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-ai-engine.md`

**产出**：

| 产出 | 内容 |
|------|------|
| ASR 模块 | 流式语音识别、多模型适配（SenseVoice/Whisper）、VAD 断句 |
| NLU 模块 | 双层意图分类（小模型 + LLM 兜底）、槽位提取、情感分析 |
| 对话管理 | 话术状态机执行引擎、上下文记忆、变量替换、知识库检索 |
| TTS 模块 | 流式语音合成、多音色适配（CosyVoice/ChatTTS）、SSML 支持 |
| 模型路由 | 按租户/场景/成本/负载路由到不同模型实例 |
| Dubbo 接口 | 实现 `CallProcessApi`（BIDIRECTIONAL_STREAM 服务端） |
| 会话管理 | 每个通话会话一个虚拟线程，Scoped Values 传递租户上下文 |

**依赖**: `vhuan-common`、`vhuan-call-api`、`vhuan-agent`（知识库检索，@HttpExchange 远程）

---

## 第三阶段：旁路支撑

> 目标：核心链路清楚后，接口契约明确，高效收尾

### 3.1 vhuan-contact（客户与线索服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-contact.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 名单管理 | 创建名单、导入（Excel/CSV/API）、导出、标签管理 |
| 线索管理 | 线索 CRUD、状态流转、去重（DB 唯一约束 + upsert） |
| 黑名单 | 号码黑名单（全局 + 租户级别）、合规勿扰时段 |
| 数据表 | `contact`、`contact_list`、`contact_list_item`、`blacklist` |

**依赖**: `vhuan-common`、`vhuan-tenant`

---

### 3.2 vhuan-analytics（数据分析服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-analytics.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 实时统计 | Kafka 消费通话事件 + Redis 聚合 + Dubbo `MetricApi` 指标接收 |
| 离线报表 | 日报/周报/月报生成、任务维度报表、坐席绩效 |
| 大屏数据 | 实时大屏数据接口（接通率/转化率/意向分布/分时趋势） |
| 数据表 | `report_daily`、`report_campaign`、`agent_performance` |

**依赖**: `vhuan-common`、Kafka 事件定义（来自 `call-service`）

---

### 3.3 vhuan-notification（通知服务）

**状态**: 设计已完成

**设计文档**: `design/vhuan-notification.md`

**产出**：

| 产出 | 内容 |
|------|------|
| 消息模板 | 模板管理、变量替换 |
| 发送渠道 | 站内信、邮件、短信、Webhook 回调 |
| 触发规则 | Kafka 事件驱动 + API 主动调用（auth 发验证码） |
| 消息记录 | 发送历史、状态追踪、失败重试、短信限流 |
| 数据表 | `message_template`、`message_record`、`channel_config` |

**依赖**: `vhuan-common`、Kafka 事件定义（来自 `call-service`）

---

### 3.4 vhuan-sip-connector（SIP 连接器）

**状态**: 设计已完成

**设计文档**: `design/vhuan-sip-connector.md`

**产出**：

| 产出 | 内容 |
|------|------|
| SIP 协议栈 | 基于 Netty 的 SIP 信令处理（Invite / Ringing / Answer / Bye / Cancel） |
| 运营商对接 | 多线路加权负载均衡、健康检查、自动故障切换 |
| 媒体代理 | RTP 抖动缓冲、RTP↔PCM 转换、媒体流代理/转发 |
| 内部接口 | 供 `call-service` 调用的内部 API（发起呼叫、挂断、媒体流订阅） |
| 数据表 | `sip_line`（共享 Schema，平台级线路资源） |

**依赖**: `vhuan-common`、运营商线路资源

---

## 设计进度总览

| 阶段 | 模块 | 设计状态 | 设计完成日期 |
|------|------|----------|--------------|
| 一 | vhuan-common | 已完成 | 2026-08-05 |
| 一 | vhuan-gateway | 已完成 | 2026-08-09 |
| 二 | vhuan-auth | 已完成 | 2026-08-09 |
| 二 | vhuan-tenant | 已完成 | 2026-08-09 |
| 二 | vhuan-agent | 已完成 | 2026-08-09 |
| 二 | vhuan-campaign | 已完成 | 2026-08-09 |
| 二 | vhuan-call | 已完成 | 2026-08-09 |
| 二 | vhuan-ai-engine | 已完成 | 2026-08-09 |
| 三 | vhuan-contact | 已完成 | 2026-08-09 |
| 三 | vhuan-analytics | 已完成 | 2026-08-09 |
| 三 | vhuan-notification | 已完成 | 2026-08-09 |
| 三 | vhuan-sip-connector | 已完成 | 2026-08-09 |

> 全部 12 个模块详细设计完成，设计文档存放于 `design/` 目录。

---

# 第二部分：编码实现计划

## 编码方向：自底向上

**编码必须自底向上推进**，原因如下：

1. **编译依赖**：`vhuan-common` 是所有模块的编译依赖，`vhuan-call-api` 被 campaign / call / ai-engine 引用。底层不先编译通过，上层无法构建。
2. **可验证性**：自底向上保证每个模块完成后即可 `mvn compile` 通过、可独立单测，错误在最早阶段暴露。
3. **接口契约先行**：`vhuan-call-api`（Dubbo 接口 + DTO）必须在被调用方（call）和调用方（campaign / ai-engine）编码前确定，避免返工。

> 设计阶段"自顶向下"用于理清需求边界；编码阶段"自底向上"用于满足编译依赖。两者互补，不是矛盾。

## 编码阶段划分

### 阶段一：工程底座

> 目标：建立 Maven 多模块工程，稳定底层编译依赖

| 序号 | 模块 | 内容 | 依赖 | 验证 |
|------|------|------|------|------|
| 1 | 父工程 pom | 依赖管理（BOM）、插件、JDK 21 + `--enable-preview` 配置、模块聚合 | 无 | `mvn compile` 通过 |
| 2 | vhuan-common | 异常体系、实体、常量、租户上下文、自动配置 | 父工程 | 单测通过、`mvn compile` 通过 |

**里程碑**：父工程 + vhuan-common 编译通过。

---

### 阶段二：Dubbo 契约 + 网关

> 目标：确定 RPC 契约模块，打通统一入口

| 序号 | 模块 | 内容 | 依赖 | 验证 |
|------|------|------|------|------|
| 3 | vhuan-call-api | `CallDispatchApi`（CLIENT_STREAM）、`CallProcessApi`（BIDI STREAM）+ DTO（Record） | vhuan-common | `mvn compile` 通过 |
| 4 | vhuan-gateway | WebFlux 路由、过滤器链（JWT → 租户注入）、Sentinel 限流、Nacos 动态路由 | vhuan-common | 网关启动，路由规则加载 |

**里程碑**：RPC 契约稳定，网关可启动。

---

### 阶段三：独立基础服务（可并行）

> 目标：实现仅依赖 common 的独立服务，各服务可独立启动

| 序号 | 模块 | 内容 | 依赖 | 验证 |
|------|------|------|------|------|
| 5 | vhuan-notification | 四渠道发送、模板管理、重试、限流 | vhuan-common | 单测 + 接口测试 |
| 6 | vhuan-auth | JWT、RBAC、API Key、登录安全 | vhuan-common | 单测 + 接口测试 |
| 7 | vhuan-tenant | 租户生命周期、SchemaManager、配额、计费 | vhuan-common | 单测 + 接口测试 |
| 8 | vhuan-sip-connector | Netty SIP 协议栈、线路管理、RTP 媒体代理 | vhuan-common | 信令单测 |

> 该阶段 4 个服务**编译层面只依赖 vhuan-common**，可并行编码。auth 调用 notification、tenant 调用 auth 均为 `@HttpExchange` 远程调用，模块间无编译耦合。

**里程碑**：4 个基础服务可独立启动、健康检查通过。

---

### 阶段四：配置与数据服务（可并行）

> 目标：实现依赖 common 的数据与配置服务，为 campaign 提供数据源

| 序号 | 模块 | 内容 | 依赖 | 验证 |
|------|------|------|------|------|
| 9 | vhuan-agent | Agent 配置、话术编排、知识库、音色 | vhuan-common | 单测 + 接口测试 |
| 10 | vhuan-contact | 名单、线索、导入、黑名单 | vhuan-common | 单测 + 接口测试 |
| 11 | vhuan-analytics | Dubbo `MetricApi`（被调用方）、Kafka 聚合、报表 | vhuan-common | 单测 + 接口测试 |

**里程碑**：配置与数据服务可独立启动，提供完整 CRUD 接口。

---

### 阶段五：核心执行链路（串行 + 并行混合）

> 目标：打通 call / ai-engine / campaign 端到端执行链路

| 序号 | 模块 | 内容 | 依赖 | 验证 |
|------|------|------|------|------|
| 12 | vhuan-ai-engine | 实现 `CallProcessApi`（BIDI 服务端）、ASR/NLU/DM/TTS、模型路由 | vhuan-common, vhuan-call-api | Dubbo 服务注册 + 流式单测 |
| 13 | vhuan-call | 状态机、SIP 对接、媒体流、录音、坐席介入 | vhuan-common, vhuan-call-api | 端到端通话流程验证 |
| 14 | vhuan-campaign | 调度引擎、Dubbo CLIENT_STREAM 下发、重试 | vhuan-common, vhuan-call-api | 端到端外呼验证 |

> ai-engine 与 call 先编码（实现 BIDI 流），campaign 最后编码（消费 call 的事件、调用 call 的下发）。三者完成后再做端到端联调。

**里程碑**：`campaign → call → ai-engine` 端到端链路打通。

---

### 阶段六：集成测试与收尾

> 目标：全系统部署联调，验证跨服务流程与多租户隔离

| 序号 | 内容 | 验证 |
|------|------|------|
| 15 | 全服务部署到 Nacos | 服务注册发现正常 |
| 16 | 端到端主流程 | 建租户 → 配话术 → 建任务 → 下发号码 → 通话 → 统计 → 通知 |
| 17 | 多租户隔离验证 | 各租户 Schema 数据隔离、配额生效 |
| 18 | 集成测试 + 性能测试 | 并发通话、高 QPS 网关 |

**里程碑**：全链路联调通过，达到验收标准。

---

## 编码进度总览

| 阶段 | 模块 | 编码状态 | 完成日期 |
|------|------|----------|----------|
| 一 | 父工程 pom | 待开始 | — |
| 一 | vhuan-common | 进行中 | — |
| 二 | vhuan-call-api | 待开始 | — |
| 二 | vhuan-gateway | 待开始 | — |
| 三 | vhuan-notification | 待开始 | — |
| 三 | vhuan-auth | 待开始 | — |
| 三 | vhuan-tenant | 待开始 | — |
| 三 | vhuan-sip-connector | 待开始 | — |
| 四 | vhuan-agent | 待开始 | — |
| 四 | vhuan-contact | 待开始 | — |
| 四 | vhuan-analytics | 待开始 | — |
| 五 | vhuan-ai-engine | 待开始 | — |
| 五 | vhuan-call | 待开始 | — |
| 五 | vhuan-campaign | 待开始 | — |
| 六 | 集成测试与收尾 | 待开始 | — |

---

> **说明**：每完成一个模块的编码，更新对应模块状态、完成日期，并补充关键实现决策记录。编码遵循 AGENTS.md 规范（复用 Hutool/Graceful Response/MyBatis-Flex/MapStruct，不重复造轮子，先列 plan 确认后再改代码）。
