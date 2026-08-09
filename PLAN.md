# 多租户 AI Agent 电话营销系统 — 详细设计计划

> **版本**: v1.0.0  
> **日期**: 2026-08-05  
> **状态**: 详细设计阶段，按阶段推进，每个模块完成后更新本文档

---

## 推进策略

**"先打地基，再走核心链路，最后补旁路"**，三阶段推进：

- **第一阶段**：基础设施层 — 所有模块的编译依赖，必须先稳定
- **第二阶段**：核心业务链路 — 系统的价值命脉，自顶向下逐层打通
- **第三阶段**：旁路支撑 — 核心链路清楚后，接口契约明确，收尾效率高

---

## 第一阶段：基础设施

> 目标：稳定所有模块的编译依赖，为后续 10 个服务的详细设计提供统一基础

### 1.1 vhuan-common（公共模块）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 统一响应体 | `Result<T>`、`PageResult<T>`，与 Graceful Response 兼容 |
| 异常体系 | 业务异常枚举 `BizErrorCode`、全局异常处理契约 |
| 租户上下文 | `TenantContext` 数据结构、`TenantContextHolder`（Scoped Values 实现） |
| 基础 DTO | 分页请求 `PageQuery`、ID 请求 `IdRequest`、审计字段基类 `BaseEntity` |
| 工具类 | 雪花算法 ID 生成器、时间工具、断言工具 |

**依赖**: 无

---

### 1.2 vhuan-gateway（API 网关）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 路由配置 | 10 个微服务的路由规则 |
| 全局过滤器 | JWT 解析 → 租户上下文注入（`X-Tenant-Id`）、请求日志、CORS |
| 限流策略 | Sentinel 按 `tenant_id` 维度限流 |
| 鉴权配置 | 白名单（登录/注册/健康检查）vs 需认证路由 |

**依赖**: `vhuan-common`

---

> **说明**：RPC 层采用 **Apache Dubbo 3 + Triple 协议**。服务契约使用纯 Java 接口定义（`@DubboService` / `@DubboReference`），DTO 定义在被调用方模块内由调用方引用。Triple 协议基于 HTTP/2，支持全双工流式（BIDIRECTIONAL_STREAM / CLIENT_STREAM / SERVER_STREAM），注册中心复用 Nacos。已移除独立 `vhuan-proto` 模块，无需 `.proto` IDL。

## 第二阶段：核心业务链路

> 目标：自顶向下打通 `auth → tenant → agent → campaign → call → ai-engine` 完整链路

### 2.1 vhuan-auth（认证授权服务）

**状态**: 设计中

**产出**：

| 产出 | 内容 |
|------|------|
| 认证接口 | 登录（账号密码 / 手机验证码）、登出、Token 刷新、强制改密 |
| JWT 管理 | Access Token（30min 无状态）+ Refresh Token（7d Redis 存储，登录时旋转） |
| RBAC 权限 | 四级角色（平台管理员 / 租户管理员 / 主管 / 坐席）、权限树、`@RequirePermission` 注解 AOP |
| 租户 API Key | 生成、校验（HMAC 签名防重放）、吊销 |
| 登录安全 | BCrypt 加密、登录失败 5 次锁定 15min |
| 数据表 | `sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`tenant_api_key` |

**依赖**: `vhuan-common`、`vhuan-notification`（短信验证码）、Nacos、Redis（Redisson）

---

### 2.2 vhuan-tenant（租户管理服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 租户管理 | 创建租户（自动创建 Schema）、启用/禁用、信息维护 |
| 套餐管理 | 套餐定义、配额模型（并发通道数、存储容量、API 频率） |
| 用量计费 | 并发通话时长累计、存储用量统计、账单生成 |
| Schema 管理 | 租户 Schema 创建/迁移脚本 |
| 数据表 | `tenant_info`、`tenant_plan`、`tenant_plan_quota`、`tenant_bill` |

**依赖**: `vhuan-common`、`vhuan-auth`

---

### 2.3 vhuan-agent（Agent 配置与话术服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| Agent 配置 | 创建/编辑 Agent、绑定音色、绑定知识库、启用/停用 |
| 话术模板 | 话术节点树 CRUD、意图分支配置、槽位定义、变量管理 |
| 知识库 | FAQ 条目管理、向量化存储（可选，视 LLM 方案决定） |
| 音色管理 | 音色列表、试听、租户自定义音色 |
| 数据表 | `agent_config`、`agent_script`、`agent_script_node`、`agent_knowledge` |

**依赖**: `vhuan-common`、`vhuan-tenant`

---

### 2.4 vhuan-campaign（外呼任务调度服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 任务管理 | 创建任务（选择 Agent + 话术 + 名单）、启停、暂停/恢复 |
| 批次管理 | 名单分批、号码分配策略（顺序/随机/权重） |
| 调度策略 | 定时外呼、预测式外呼、预览式外呼 |
| 号码下发 | 通过 Dubbo Triple CLIENT_STREAM 向 `call-service` 批量下发号码 |
| 重试策略 | 未接通号码的重试次数、间隔、时段限制 |
| 数据表 | `campaign`、`campaign_batch`、`campaign_detail` |

**依赖**: `vhuan-common`、`vhuan-agent`、`vhuan-contact`

---

### 2.5 vhuan-call（通话管理服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 通话状态机 | 状态枚举（Idle → Dialing → Ringing → Answered → InProgress → Ended）及转换规则 |
| SIP 信令 | 对接 `sip-connector`，发起 Invite、处理响应、挂断 |
| 媒体流管理 | 音频流接收/转发、RTP 包处理 |
| AI 引擎交互 | 通过 Dubbo Triple BIDIRECTIONAL_STREAM 推送音频、接收转写 + TTS 指令 |
| 坐席介入 | 监听（订阅转写流）、切入（媒体流切换）、切出（恢复 AI） |
| 录音管理 | 录音启动/停止、文件上传 MinIO |
| 并发控制 | 租户并发通道校验、分配/释放 |
| 数据表 | `call_session`、`call_recording`、`call_transcript`、`call_intent_result`、`call_slot` |

**依赖**: `vhuan-common`、`vhuan-ai-engine`、`vhuan-tenant`

---

### 2.6 vhuan-ai-engine（AI 引擎服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| ASR 模块 | 流式语音识别接口、多模型适配（SenseVoice/Whisper）、VAD 断句 |
| NLU 模块 | 意图分类（小模型 + LLM 兜底）、槽位提取、情感分析 |
| 对话管理 | 话术状态机执行引擎、上下文记忆管理、多轮策略 |
| TTS 模块 | 流式语音合成、多音色适配、SSML 支持 |
| 模型路由 | 按租户/场景/成本路由到不同模型实例 |
| Dubbo 接口 | BIDIRECTIONAL_STREAM 对接 `call-service`、SERVER_STREAM 推送转写到监控面板 |
| 会话管理 | 虚拟线程绑定，每个通话会话一个虚拟线程，Scoped Values 传递租户上下文 |

**依赖**: `vhuan-common`、`vhuan-agent`

---

## 第三阶段：旁路支撑

> 目标：核心链路清楚后，接口契约明确，高效收尾

### 3.1 vhuan-contact（客户与线索服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 名单管理 | 创建名单、导入（Excel/CSV/API）、导出、标签管理 |
| 线索管理 | 线索 CRUD、状态流转、去重 |
| 黑名单 | 号码黑名单、全局+租户级别、合规勿扰时段 |
| 数据表 | `contact`、`contact_list`、`contact_list_item`、`blacklist` |

**依赖**: `vhuan-common`、`vhuan-tenant`

---

### 3.2 vhuan-analytics（数据分析服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 实时统计 | 当日通话量、接通率、转化率、意向分布（通过 Kafka 消费 + Dubbo Triple 指标接收） |
| 离线报表 | 日报/周报/月报生成、任务维度报表、坐席绩效 |
| 大屏数据 | 实时大屏数据接口 |
| 数据表 | `report_daily`、`report_campaign` |

**依赖**: `vhuan-common`、Kafka 事件定义（来自 `call-service`）

---

### 3.3 vhuan-notification（通知服务）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| 消息模板 | 模板管理、变量替换 |
| 发送渠道 | 站内信、邮件、短信、Webhook 回调 |
| 触发规则 | 通话结束通知、任务完成通知、告警通知 |
| 消息记录 | 发送历史、状态追踪 |

**依赖**: `vhuan-common`、Kafka 事件定义（来自 `call-service`）

---

### 3.4 vhuan-sip-connector（SIP 连接器）

**状态**: 待开始

**产出**：

| 产出 | 内容 |
|------|------|
| SIP 协议栈 | 基于 Netty 的 SIP 信令处理（Invite / Ringing / Answer / Bye / Cancel） |
| 运营商对接 | 多线路管理、线路健康检查、自动故障切换 |
| 媒体代理 | RTP 媒体流代理/转发 |
| 内部接口 | 供 `call-service` 调用的内部 API（发起呼叫、挂断、媒体流订阅） |

**依赖**: `vhuan-common`、运营商线路资源

---

## 进度总览

| 阶段 | 模块 | 状态 | 完成日期 |
|------|------|------|----------|
| 一 | vhuan-common | 已完成 | 2026-08-05 |
| 一 | vhuan-gateway | 已完成 | 2026-08-09 |
| 二 | vhuan-auth | 设计中 | — |
| 二 | vhuan-tenant | 设计中 | — |
| 二 | vhuan-agent | 设计中 | — |
| 二 | vhuan-campaign | 设计中 | — |
| 二 | vhuan-call | 设计中 | — |
| 二 | vhuan-ai-engine | 设计中 | — |
| 三 | vhuan-contact | 待开始 | — |
| 三 | vhuan-analytics | 待开始 | — |
| 三 | vhuan-notification | 待开始 | — |
| 三 | vhuan-sip-connector | 待开始 | — |

---

> **说明**：每完成一个模块的详细设计，将更新对应模块的状态、完成日期，并补充设计过程中的关键决策记录。详细设计文档存放在 `design/` 目录下。