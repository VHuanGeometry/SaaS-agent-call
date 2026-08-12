# vhuan-gateway 模块教学指南

> 面向小白：用大白话讲清楚这个"API 网关"模块用到了哪些技术、哪些设计模式，以及它们各自扮演什么角色。
> 阅读建议：先看第 1 节建立整体印象，再按顺序阅读，遇到不熟的术语可先跳过，看到"类比"部分再回头理解。

---

## 目录

1. [这个模块是干嘛的？](#1-这个模块是干嘛的)
2. [一个请求进来，会发生什么（主流程）](#2-一个请求进来会发生什么主流程)
3. [核心技术清单](#3-核心技术清单)
4. [设计模式讲解](#4-设计模式讲解)
5. [各文件职责速查表](#5-各文件职责速查表)
6. [常见疑问（FAQ）](#6-常见疑问faq)

---

## 1. 这个模块是干嘛的？

把系统想象成一个大公司，有很多个**部门**（微服务）：`vhuan-auth`（认证）、`vhuan-tenant`（租户）、`vhuan-call`（通话）……每个部门有自己的"办公室"（独立端口/进程）。

**网关（Gateway）就是公司前台**。所有外部请求先到前台，前台做几件事：

- **认人**：你是谁？（JWT 鉴权）
- **带路**：你要找哪个部门？（路由转发）
- **登记**：你是哪个租户的？带个工牌过去（租户上下文透传）
- **控流**：人太多时拦一拦（限流）
- **留痕**：记下每次来访记录（日志、TraceId 追踪）

这样各业务部门就不用自己重复做这些事，专心干自己的活。

---

## 2. 一个请求进来，会发生什么（主流程）

```
浏览器/APP 请求
     │
     ▼
┌──────────────────────────────┐   Order -3（最先执行）
│ TraceIdFilter                │   生成/沿用 X-Trace-Id，写入 MDC 用于日志
└──────────────────────────────┘
     ▼
┌──────────────────────────────┐   Order -2
│ RequestLogFilter             │   记录开始时间，结束时算耗时
└──────────────────────────────┘
     ▼
┌──────────────────────────────┐   Order 0
│ AuthGlobalFilter             │   白名单放行 / 内部调用校验 / JWT 鉴权，
│                              │   解析后把 userId、tenantId、roles 写入请求头
└──────────────────────────────┘
     ▼
┌──────────────────────────────┐   Order 1
│ TenantContextFilter          │   保证每个请求都有 X-Tenant-Id
└──────────────────────────────┘
     ▼
┌──────────────────────────────┐   Sentinel（内置过滤器）
│ SentinelGatewayFilter        │   按规则限流
└──────────────────────────────┘
     ▼
         路由转发（lb://vhuan-xxx） → Nacos 找到实例 → 负载均衡 → 转发给业务服务
```

> 数字（Order）越小越先执行。负数在前、正数在后，这就是**过滤器链**。

---

## 3. 核心技术清单

### 3.1 Spring Cloud Gateway + WebFlux（响应式）

- **它是什么**：官方 API 网关，基于响应式编程（Reactive）。核心是 **Netty**（非阻塞 I/O 服务器），不是传统的 Tomcat（阻塞式）。
- **为什么用**：网关要同时服务大量并发连接，非阻塞模型下单个线程能处理很多连接，省内存、抗高并发。
- **你看到的体现**：
  - 过滤器方法返回 `Mono<Void>`（响应式类型），不是直接 `void`。
  - 用 `chain.filter(exchange)` 把请求"传下去"，再用 `.doFinally(...)` 在结束后做收尾。
- **注意点**：WebFlux 与 Spring MVC（Tomcat）**不能共存**，所以 pom 里排除了 `spring-boot-starter-web`。

### 3.2 Nacos（服务注册与发现）

- **它是什么**：一个"通讯录"。每个微服务启动时把自己的地址登记到 Nacos；网关转发前先查 Nacos，找到目标服务有哪些实例。
- **你看到的体现**：路由 `uri: lb://vhuan-auth`。`lb://` 意思是"负载均衡地发给叫 vhuan-auth 的服务"。
- **预热器**：`GatewayApplication.discoveryWarmer` 在启动时提前查一次服务列表，把"首次查询慢"的开销提前，避免第一个用户请求超时。

### 3.3 Spring Cloud LoadBalancer（负载均衡）

- **它是什么**：当目标服务有多个实例时，决定"这次发给哪个实例"（默认轮询）。
- **为什么需要显式加依赖**：Gateway 本身只带 `loadbalancer` 的纯库，不显式引入 starter，负载均衡的 Bean 不会自动装配，请求会走 503。

### 3.4 JWT（JSON Web Token）+ jjwt

- **它是什么**：一种"带签名的身份凭证"。用户登录后，认证服务签发一个 JWT 给客户端；客户端之后每次请求都带上它；网关验证签名和有效期即可确认身份，无需查数据库。
- **jwt.secret**：签名密钥，签名和验签用同一个密钥（本项目 HS256 对称加密）。
- **你看到的体现**：`JwtUtil.parseToken(token)` 用 `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)` 解析。
- **区分**：Gateway 只负责**解析验证**，不负责**生成**；生成由 `vhuan-auth` 完成。

### 3.5 Sentinel（限流）

- **它是什么**：阿里开源的流量防护组件。网关用它做限流，防止某个接口被刷爆或某租户过度占用资源。
- **你看到的体现**：`SentinelConfig` 定义 API 分组（`ApiPathPredicateItem` 匹配 URL）和限流规则（`GatewayFlowRule`），如全局 10000 QPS、登录接口 100 次/分钟/IP。
- **热更新**：规则还可以放 Nacos，动态刷新不用重启。

### 3.6 CORS（跨域）

- **它是什么**：浏览器安全机制，跨域请求需要服务器明确"允许"。前端跑在 `localhost:5173`，后端在 `localhost:8000`，属于跨域。
- **你看到的体现**：`application.yml` 的 `spring.cloud.gateway.globalcors` 配置允许的来源、方法、请求头。

### 3.7 MDC（日志上下文）+ TraceId

- **它是什么**：MDC（Mapped Diagnostic Context）是 SLF4J 提供的一个"当前线程的日志上下文小本本"，往里放 key-value，日志格式里用 `%X{traceId}` 就能打印出来。
- **为什么**：一个请求会经过网关再到多个服务，每个请求分配唯一 `X-Trace-Id`，贯穿全链路，出问题时能按 traceId 把所有日志串起来查。

---

## 4. 设计模式讲解

> 设计模式是前人总结的"常见问题的成熟解法"。这里只讲本模块真正用到的，不堆概念。

### 4.1 责任链模式（Chain of Responsibility）★ 核心

- **一句话**：让请求依次经过一串"处理器"，每个处理器决定自己处理还是传给下一个。
- **本模块体现**：Spring Cloud Gateway 的 `GlobalFilter` 链。每个过滤器实现 `GlobalFilter` 接口，用 `Ordered.getOrder()` 决定先后顺序，`chain.filter(exchange)` 表示"传给下一个"。
- **类比**：公司请假审批——员工 → 组长 → 经理 → 老板，每个人看不该自己批就往下传。
- **好处**：增删一个环节（过滤器）不影响其他环节，扩展方便。

### 4.2 控制反转 / 依赖注入（IoC / DI）

- **一句话**：对象不自己 new 依赖，而是由 Spring 容器创建好"喂"进来。
- **本模块体现**：`AuthGlobalFilter` 构造方法接收 `GatewaySecurityProperties` 和 `JwtUtil`，由 Spring 自动注入；类上标 `@Component` 让 Spring 管理。
- **好处**：解耦、方便替换和测试。

### 4.3 构建者模式（Builder）

- **一句话**：一个对象属性很多、配置步骤繁琐时，用"链式调用"一步步搭，最后 `.build()` 产出对象。
- **本模块体现**：
  - `exchange.getRequest().mutate().header(...).build()` —— 构建"修改后的请求"。
  - `Jwts.parser().verifyWith(key).build()` —— 构建解析器。
  - `new ApiPathPredicateItem().setPattern(...).setMatchStrategy(...)` —— 链式 set。
- **好处**：代码可读、不易写错参数顺序。

### 4.4 外观模式（Facade）

- **一句话**：用一个简单的门面类，封装一堆复杂的内部操作，对外只暴露简洁接口。
- **本模块体现**：`JwtUtil` 把 jjwt 复杂的解析 API 封装成一行 `parseToken(token)`；`GatewayApplication` 把整个应用启动封装起来。
- **好处**：调用方不用关心 jjwt 内部细节。

### 4.5 单例模式（Singleton）

- **一句话**：整个应用只创建一份实例，大家共用。
- **本模块体现**：Spring 默认管理的 Bean 都是单例（`@Component`、`@Configuration` 的类默认单例）。
- **好处**：省内存；像 `JwtUtil` 只需一个密钥实例。

### 4.6 策略模式（Strategy）— 变体

- **一句话**：同一件事有不同的做法，把它们各自封装，运行时可切换。
- **本模块体现**：限流"按什么维度"（IP、租户、全局）通过 `GatewayParamFlowItem` 的 `parseStrategy` 指定，即策略可选。
- **备注**：本模块只是**用到了**这个思想（Sentinel 内部实现），并没有自己写策略类，属于"框架帮你实现了策略"。

### 4.7 模板方法 / 生命周期钩子（Template Method / Hook）

- **一句话**：框架定好"骨架流程"，你在约定的时机插入自己的代码。
- **本模块体现**：`@PostConstruct`（Bean 初始化后执行）、`ApplicationRunner`（应用启动完成后执行）、`GlobalFilter.filter(...)`（请求到来时执行）。
- **好处**：你只写"做什么"，"何时做"由框架保证。

---

## 5. 各文件职责速查表

| 文件 | 一句话职责 | 关键点 |
|------|-----------|--------|
| `GatewayApplication` | 启动类 | `@SpringBootApplication`、`discoveryWarmer` 预热服务 |
| `pom.xml` | 依赖清单 | 排除 MVC 依赖、补 Sentinel/jjwt/Hutool |
| `application.yml` | 全局配置 | 端口、路由、白名单、CORS、Sentinel、JWT 密钥 |
| `filter/TraceIdFilter` | 生成/透传 TraceId | Order -3，写 MDC |
| `filter/RequestLogFilter` | 记请求日志 | Order -2，算耗时 |
| `filter/AuthGlobalFilter` | JWT 鉴权 + 注入用户头 | Order 0，白名单/内部调用/JWT |
| `filter/TenantContextFilter` | 保证有 X-Tenant-Id | Order 1 |
| `util/JwtUtil` | 封装 jjwt 解析 | 读 jwt.secret，`parseToken()` |
| `config/GatewaySecurityProperties` | 读取白名单配置 | `@ConfigurationProperties` |
| `config/SentinelConfig` | 加载限流规则 | 全局 + 登录/IP 限流 |
| `config/CorsConfig`（已删除） | （原自定义 CORS） | 改为用 globalcors |

---

## 6. 常见疑问（FAQ）

**Q1：为什么过滤器要返回 `Mono<Void>` 而不是 `void`？**
A：因为 WebFlux 是响应式的。`void` 是"同步做完就走"，`Mono<Void>` 表示"异步的结果容器"，网关可以异步转发、异步收尾，不阻塞线程。

**Q2：为什么改请求头要 `exchange.mutate().request(newReq).build()`？**
A：`exchange.getRequest()` 是不可变对象，`.mutate()` 只是返回一个"修改副本的构建器"，**不修改原对象**。必须 `.build()` 出新请求，再 `exchange.mutate().request(newReq).build()` 生成新 exchange 传下去，改动才生效。这是本模块修复过的一个经典坑。

**Q3：白名单和路由是什么关系？**
A：路由负责"把 `/api/auth/**` 转发到 vhuan-auth"；白名单负责"哪些路径不需要 JWT"。两者独立。白名单路径通常也是放行的公开接口（如登录）。

**Q4：为什么排除 `spring-boot-starter-web`？**
A：Gateway 基于 WebFlux（Netty）。如果 classpath 里同时有 Spring MVC（Tomcat），Spring Boot 启动时会纠结"到底按哪种跑"，可能误判成 Servlet 应用导致启动失败。所以必须二选一。

**Q5：`lb://` 前面的 `lb` 是什么意思？**
A：LoadBalancer（负载均衡）的缩写。它告诉网关：这个 URI 不是真实地址，而是"服务名"，请先通过 Nacos 找到该服务的所有实例，再用负载均衡挑一个转发。

**Q6：Token 解析失败会怎样？**
A：`AuthGlobalFilter` 捕获 `ExpiredJwtException`（过期）和 `JwtException`（无效），返回 401，响应体统一为 `{code, message, data}`，方便前端统一处理。

---

> 本指南基于 vhuan-gateway 模块当前代码编写（2026-08-12）。
> 建议配合 [AGENTS.md](../AGENTS.md) 和设计文档 [vhuan-gateway.md](../design/vhuan-gateway.md) 一起阅读。
