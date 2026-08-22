# AGENTS.md

> 本项目 AI 辅助开发的通用规则，适用于所有模块的详细设计和编码阶段。

---

## 1. 不重复造轮子

**优先使用成熟的开源项目，禁止重新实现已有标准库或知名开源项目提供的功能。**

在进行详细设计和编码时，始终遵循以下决策流程：

1. **先查 JDK 标准库** — 如 `java.util`、`java.time`、`java.util.stream`
2. **再查已集成的开源项目** — 如 Spring Framework、MyBatis-Flex、Hutool、Graceful Response、MapStruct
3. **最后才考虑自己实现** — 仅在以上两者都无法满足需求时

**推荐的开源项目**：

| 项目 | 用途 | 说明 |
|------|------|------|
| **Hutool** | 通用工具类库 | 日期、字符串、集合、断言、JSON、加密、雪花 ID 等，覆盖 90% 的通用工具需求 |
| **Graceful Response** | 统一响应体 + 异常映射 | Spring Boot 场景下自动包装 Controller 返回值，`@ExceptionMapper` 声明式异常映射 |
| **MyBatis-Flex** | 轻量 ORM + 分页 | 已集成，提供 `Page<T>` 分页模型，`QueryWrapper` 查询构造 |
| **MapStruct** | 对象转换 | 编译期生成 PO ↔ DTO ↔ VO 转换代码，性能优于反射框架 |
| **Redisson** | 分布式锁 + 缓存 | 已集成，基于 Redis 的分布式锁、集合、队列 |
| **Knife4j** | API 文档 | 已集成，基于 SpringDoc 的增强 UI |

**反例**（禁止做的事情）：

- 自己实现 `DateTimeUtils` 而不是用 Hutool 的 `DateUtil`
- 自己实现 `AssertUtils` 而不是用 Hutool 的 `Assert`
- 自己实现雪花 ID 算法而不是用 Hutool 的 `IdUtil.getSnowflake()`
- 自己实现 `PageResult<T>` 而不是用 MyBatis-Flex 的 `Page<T>`
- 自己实现 `GlobalExceptionHandler` 而不是用 Graceful Response 的 `@ExceptionMapper`

---

## 2. 编码规范

### 2.1 命名约定

- 使用有意义的、描述性的名称
- 遵循项目或语言的命名规范
- 避免缩写和单字母变量（除非是约定俗成的，如循环中的 `i`）

### 2.2 代码组织

- 相关代码放在一起
- 函数只做一件事
- 保持适当的抽象层次

### 2.3 注释与文档

- 注释应该解释为什么，而不是做什么
- 为公共 API 提供清晰的文档
- 更新注释以反映代码变化

---

## 3. 性能优化

### 3.1 内存优化

- 避免不必要的对象创建
- 及时释放不再需要的资源
- 注意内存泄漏问题

### 3.2 计算优化

- 避免重复计算
- 使用适当的数据结构和算法
- 延迟计算直到必要时

### 3.3 并行优化

- 识别可并行化的任务
- 避免不必要的同步
- 注意线程安全问题

---

## 4. 通用编码规则

- 避免不必要的对象复制或克隆
- 避免多层嵌套，提前返回
- 使用适当的并发控制机制
- 当生成的代码超过 20 行时，优先考虑是否可以进行适当的抽象或聚合

---

## 5. 开发流程

- 使用中文沟通
- 参考原代码风格，包括分层、缩进和命名
- 不重复造轮子，但也不要影响其他功能
- 遇到歧义时必须确认
- 遇到风险点标记 TODO
- 先列出 plan，确认后再改动本地代码文件
- 完成代码修改后进行自检：是否所有新增接口都已实现、是否所有修改都已正确改动、业务规则是否完全覆盖、是否引入了与本次需求无关的改动、编译能否通过
- 任务完成后需要更新 AGENTS.md

---

## 6. 第一阶段设计总结（基础设施）

> 以下决策来自第一阶段三个模块的详细设计，后续模块必须遵循。

### 6.1 vhuan-common — 公共基础设施

| 决策 | 结论 |
|------|------|
| 统一响应体 | 复用 **Graceful Response**，Controller 自动包装，`BizException` 继承 `GracefulResponseException(code, msg)` 动态传入错误码 |
| 异常枚举 | `BizErrorCode` 为普通枚举（graceful-response 3.4.0 无 `GracefulResponseEnumInterface`），作为 code/msg 载体，按模块划分错误码区间（1000-1999 公共，2000-2999 auth，以此类推） |
| 全局异常处理 | **不写 GlobalExceptionHandler**，Graceful Response 的 `GlobalExceptionAdvice` 自动捕获 `GracefulResponseException` 读取 code/msg，参数校验异常和兜底异常由框架默认处理 |
| 工具类 | **全部复用 Hutool**：`IdUtil`（雪花 ID）、`DateUtil`（日期）、`StrUtil`（字符串）、`Assert`（断言）、`CollUtil`（集合）、`JSONUtil`（JSON） |
| 雪花 ID | 使用 Hutool `IdUtil.getSnowflake(workerId, datacenterId)`，workerId 从 Nacos/K8s 环境变量注入 |
| 分页模型 | 复用 MyBatis-Flex 的 `Page<T>`，不自定义 `PageQuery`/`PageResult` |
| 租户上下文 | `TenantContext` 使用 JDK Record（不可变），`TenantContextHolder` 使用 **Scoped Values**（`java.lang.ScopedValue`，非 ThreadLocal），适配虚拟线程。TODO：JDK 21 为预览特性，需 `--enable-preview`，JDK 24 转正后移除 |
| 依赖边界 | `vhuan-common` 不依赖 MyBatis-Flex、Nacos、Redis；`BaseEntity` 不含 ORM 注解，由业务模块子类添加 |

### 6.2 vhuan-proto → Dubbo RPC 契约

> **注意**：已将 RPC 框架从 gRPC 更换为 **Apache Dubbo 3 + Triple 协议**，并删除独立 `vhuan-proto` 模块。以下为 Dubbo 的契约设计约束。

| 决策 | 结论 |
|------|------|
| 服务契约 | 使用 **纯 Java 接口** 定义（`@DubboService` / `@DubboReference`），DTO 定义在**被调用方服务模块**内，由调用方通过 Maven 依赖引用，**无需 `.proto` IDL** |
| 协议 | **Triple**（基于 HTTP/2），支持全双工流式（BIDIRECTIONAL_STREAM / CLIENT_STREAM / SERVER_STREAM），兼容 gRPC 生态 |
| 流式 API | 使用 `org.apache.dubbo.common.stream.StreamObserver<T>`；双向流/客户端流：方法返回 `StreamObserver`，服务端流：方法第 2 个参数为 `StreamObserver` |
| 租户上下文传递 | 通过 **Dubbo RpcContext / Attachment** 或 Triple 协议头传递（`tenant-id`、`tenant-name`、`plan-code`），服务端 `Filter` 统一解析，不在 DTO 中重复携带 |
| 注册中心 | 复用 **Nacos**，Dubbo 服务自动注册与发现 |
| 服务治理 | 内置负载均衡、限流、熔断、重试，无需额外中间件 |
| 音频流 | `call ↔ ai-engine` 使用 **BIDIRECTIONAL_STREAM**（方法签名：`StreamObserver<CallRequest> processCall(StreamObserver<CallResponse> response)`） |
| 号码下发 | `campaign → call` 使用 **CLIENT_STREAM** |
| 监控推送 | `ai-engine → call → WebSocket → 前端`，ai-engine 不直接暴露给前端 |
| 指标上报 | `analytics` 使用 **UNARY**（单条）与 **CLIENT_STREAM**（批量）两种方式 |
| 端口策略 | Dubbo 端口与 HTTP 端口分离，独立防火墙和负载均衡 |

### 6.3 vhuan-gateway — API 网关

| 决策 | 结论 |
|------|------|
| 路由配置 | YAML + **Nacos 动态刷新**，修改路由无需重启 |
| 过滤器链 | 顺序：TraceId（-3）→ 请求日志（-2）→ CORS（-1）→ **JWT 认证（0）** → 租户注入（1） |
| 白名单 | `/api/auth/**`、`/v3/api-docs/**`、`/doc.html`、`/actuator/health` |
| 内部调用 | `/api/internal/**` 路径通过 `X-Internal-Call` Token 校验，**不走 JWT** |
| 限流 | 四维度：全局 10000 QPS、租户按套餐分档、接口级（登录 100 次/分钟/IP）、IP 级 100 QPS |
| 文档聚合 | Knife4j 基于服务发现自动聚合，`http://gateway:8080/doc.html` |
| 运行模式 | **WebFlux（Netty）**，不引入 Tomcat；`vhuan-common` 依赖需排除 `spring-boot-starter-web` |
| 限流方案 | Sentinel Gateway 专用适配器，规则持久化到 Nacos |

### 6.4 全局约束

- HTTP 同步调用使用 `@HttpExchange`（Spring 6 内置），不引入 OpenFeign
- RPC 使用 **Dubbo 3 + Triple 协议**（Java 接口契约），仅用于流式场景（音频流、批量下发、监控推送、指标上报），常规 CRUD 走 HTTP；**不再使用 gRPC，也不存在独立 proto 模块**
- Dubbo 服务接口（Interface）与 DTO 定义在被调用方服务模块内，调用方通过 Maven 依赖引用
- 租户上下文在 HTTP 中用 `X-Tenant-Id` 请求头传递，在 Dubbo 中用 RpcContext/Attachment 传递，在 Kafka 中用消息体 `tenant_id` 字段传递
- Gateway 不处理 Dubbo 流量、WebSocket 流量、SIP 信令
- Dubbo 流式方法统一使用 `org.apache.dubbo.common.stream.StreamObserver<T>`，禁止混用其他流式 API

---

> **版本**: v1.2.0  
> **日期**: 2026-08-05
## 7. vhuan-gateway 修复记录（2026-08-12）

网关实现要点，后续模块与网关相关改动需遵循：

- WebFlux 中修改请求头必须 `exchange.mutate().request(builder.build()).build()` 重建 exchange，仅 `getRequest().mutate().header(...)` 不会生效（AuthGlobalFilter / TenantContextFilter / TraceIdFilter 均已按此修正）
- gateway 依赖 vhuan-common 时必须排除 spring-boot-starter-web 与 graceful-response，否则 classpath 同时存在 MVC 与 WebFlux 会令 WebApplicationType 误判为 Servlet 导致启动失败
- JWT 解析放在 vhuan-gateway.util.JwtUtil（jjwt 0.12，parser().verifyWith(key)），密钥来自 jwt.secret；Gateway 只解析不签发，Token 由 vhuan-auth 生成
- 鉴权白名单从 gateway.security.whitelist-paths（@ConfigurationProperties）读取，禁止硬编码在过滤器里；路径前缀匹配放行
- CORS 统一走 spring.cloud.gateway.globalcors（yml），不再自定义 WebFilter，避免与内置 CorsWebFilter 重复/冲突
- Sentinel 使用 spring-cloud-alibaba-sentinel-gateway（网关专用适配器），编程式加载全局/登录/IP 限流规则；租户维度规则经 Nacos gw-flow 数据源热更新
- discoveryWarmer 预热的服务名必须与路由 lb:// 标识一致（当前为 vhuan-auth 等 9 个服务）
- Nacos 配置中心使用 `spring.config.import` 显式声明 dataId（`optional:nacos:${spring.application.name}.yaml`、`optional:nacos:vhuan-shared.yaml`）；`spring.cloud.nacos.config.shared-configs`/`extension-configs` 仅在 bootstrap 模式生效，`spring.config.import` 模式下不会被加载，共享配置必须放进 import 列表（vhuan-gateway 已按此修正）
- **vhuan-gateway 配置中心迁移（2026-08-22）**：已迁至 Nacos `vhuan-gateway.yaml`（命名空间 vhuan、DEFAULT_GROUP、YAML）的配置：`spring.cloud.gateway.routes`（11 条路由）、`spring.cloud.gateway.globalcors`、`gateway.security.whitelist-paths`、`logging.level`，完整参考内容存放于仓库 `config/vhuan-gateway.yaml`，可直接整段粘贴到 Nacos 控制台；以上均支持热更新免重启。**保留本地不可迁移**：`server.port`、`spring.application.name`、Nacos 连接配置（discovery/config）、`gateway.security.internal-call-token`（环境变量注入）、`spring.cloud.sentinel`、`management`、`graceful-response`。白名单属性类 `GatewaySecurityProperties` 已加 `@RefreshScope` 支持热更新；`jwt.secret` 属启动期常量（JwtUtil 缓存为 SecretKey），不加 `@RefreshScope`
