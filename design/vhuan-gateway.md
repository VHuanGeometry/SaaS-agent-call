# vhuan-gateway 详细设计

> **模块**: vhuan-gateway（API 网关）  
> **阶段**: 第一阶段 — 基础设施  
> **版本**: v1.0.0  
> **日期**: 2026-08-05  
> **状态**: 设计中

---

## 1. 设计目标

作为系统的统一入口，承担请求路由、认证鉴权、租户上下文注入、限流、请求日志和 CORS 处理。基于 Spring Cloud Gateway 实现，不编写业务逻辑。

**职责边界**：
- 路由转发：将 HTTP 请求按路径前缀转发到对应的微服务
- 认证鉴权：JWT 校验，白名单放行，非法 Token 拦截
- 租户注入：从 JWT 解析 `tenant_id`，写入 `X-Tenant-Id` 请求头，透传给下游
- 限流保护：按 `tenant_id` 维度限流，防止单租户过载
- 请求日志：记录请求路径、耗时、状态码
- CORS：统一处理跨域

**非职责**（不归 Gateway 管）：
- gRPC 流量（走独立端口，不经过 Gateway）
- WebSocket 流量（直连 `call-service` 的 netty-socketio 端口）
- SIP 信令（走独立 SIP Proxy）

---

## 2. 模块结构

```
vhuan-gateway/
├── pom.xml
├── src/main/java/com/vhuan/gateway/
│   ├── GatewayApplication.java           # 启动类
│   ├── config/
│   │   ├── RouteConfig.java              # 路由定义（YAML 或 Java DSL）
│   │   ├── SentinelConfig.java           # Sentinel 限流规则配置
│   │   └── CorsConfig.java               # CORS 配置
│   ├── filter/
│   │   ├── AuthGlobalFilter.java         # JWT 认证过滤器（全局）
│   │   ├── TenantContextFilter.java      # 租户上下文注入过滤器
│   │   ├── RequestLogFilter.java         # 请求日志过滤器
│   │   └── TraceIdFilter.java           # TraceId 生成与传递
│   └── util/
│       └── JwtUtil.java                  # JWT 解析工具（复用 Hutool + jjwt）
│
└── src/main/resources/
    ├── application.yml                    # 主配置
    └── sentinel-rules.json               # Sentinel 限流规则（可选，也可用 Nacos 动态配置）
```

---

## 3. 路由配置

### 3.1 路由表

| 路由 ID | 路径前缀 | 目标服务 | 需认证 | 说明 |
|---------|----------|----------|--------|------|
| auth-route | `/api/auth/**` | vhuan-auth | 否 | 登录/注册/Token 刷新，白名单 |
| tenant-route | `/api/tenant/**` | vhuan-tenant | 是 | 租户管理 |
| agent-route | `/api/agent/**` | vhuan-agent | 是 | Agent 配置 |
| campaign-route | `/api/campaign/**` | vhuan-campaign | 是 | 外呼任务 |
| call-route | `/api/call/**` | vhuan-call | 是 | 通话管理 |
| contact-route | `/api/contact/**` | vhuan-contact | 是 | 客户线索 |
| analytics-route | `/api/analytics/**` | vhuan-analytics | 是 | 数据分析 |
| notification-route | `/api/notification/**` | vhuan-notification | 是 | 通知管理 |
| sip-route | `/api/sip/**` | vhuan-sip-connector | 是 | SIP 线路管理 |
| knif4j-docs | `/v3/api-docs/**`, `/doc.html` | 各服务 | 否 | Knife4j 文档聚合，白名单 |

**说明**：
- `ai-engine-service` 不对外暴露 HTTP 接口，仅通过 gRPC 与 `call-service` 通信，因此不在路由表中
- `/api/internal/**` 路径保留，供服务间 @HttpExchange 调用使用，仅放行服务间内部流量（通过 `X-Internal-Call` 请求头校验，不走 JWT 校验）

### 3.2 路由配置示例

```yaml
spring:
  cloud:
    gateway:
      routes:
        # ========== 认证服务（白名单） ==========
        - id: auth-route
          uri: lb://vhuan-auth
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=0

        # ========== 租户管理 ==========
        - id: tenant-route
          uri: lb://vhuan-tenant
          predicates:
            - Path=/api/tenant/**
          filters:
            - StripPrefix=0

        # ========== Agent 配置 ==========
        - id: agent-route
          uri: lb://vhuan-agent
          predicates:
            - Path=/api/agent/**
          filters:
            - StripPrefix=0

        # ========== 外呼任务 ==========
        - id: campaign-route
          uri: lb://vhuan-campaign
          predicates:
            - Path=/api/campaign/**
          filters:
            - StripPrefix=0

        # ========== 通话管理 ==========
        - id: call-route
          uri: lb://vhuan-call
          predicates:
            - Path=/api/call/**
          filters:
            - StripPrefix=0

        # ========== 客户线索 ==========
        - id: contact-route
          uri: lb://vhuan-contact
          predicates:
            - Path=/api/contact/**
          filters:
            - StripPrefix=0

        # ========== 数据分析 ==========
        - id: analytics-route
          uri: lb://vhuan-analytics
          predicates:
            - Path=/api/analytics/**
          filters:
            - StripPrefix=0

        # ========== 通知管理 ==========
        - id: notification-route
          uri: lb://vhuan-notification
          predicates:
            - Path=/api/notification/**
          filters:
            - StripPrefix=0

        # ========== SIP 线路管理 ==========
        - id: sip-route
          uri: lb://vhuan-sip-connector
          predicates:
            - Path=/api/sip/**
          filters:
            - StripPrefix=0

        # ========== Knife4j 文档聚合 ==========
        - id: api-docs-auth
          uri: lb://vhuan-auth
          predicates:
            - Path=/v3/api-docs/auth
          filters:
            - RewritePath=/v3/api-docs/auth, /v3/api-docs

        - id: api-docs-tenant
          uri: lb://vhuan-tenant
          predicates:
            - Path=/v3/api-docs/tenant
          filters:
            - RewritePath=/v3/api-docs/tenant, /v3/api-docs

        # ... 其他服务的 api-docs 路由类似
```

**设计决策**：路由配置使用 YAML 而非 Java DSL。YAML 方式修改路由无需重新编译，配合 Nacos 配置中心可实现动态路由刷新。

---

## 4. 过滤器链

### 4.1 过滤器执行顺序

```
请求进入
  │
  ▼
┌──────────────────┐  Order: -3
│ TraceIdFilter     │  生成/提取 X-Trace-Id，设置 MDC
└────────┬─────────┘
         │
         ▼
┌──────────────────┐  Order: -2
│ RequestLogFilter  │  记录请求开始时间
└────────┬─────────┘
         │
         ▼
┌──────────────────┐  Order: -1
│ CorsFilter        │  Spring Cloud Gateway 内置，通过 CorsConfig 配置
└────────┬─────────┘
         │
         ▼
┌──────────────────┐  Order: 0
│ AuthGlobalFilter  │  JWT 校验（白名单跳过）
│                   │  解析 userId、tenantId、roles
└────────┬─────────┘
         │
         ▼
┌──────────────────┐  Order: 1
│TenantContextFilter│  从 JWT claims 提取 tenant_id
│                   │  注入 X-Tenant-Id 请求头
└────────┬─────────┘
         │
         ▼
┌──────────────────┐  Order: 2
│SentinelRateLimiter│  按 tenant_id 维度限流
│  (GatewayFilter) │
└────────┬─────────┘
         │
         ▼
    路由转发到目标服务
```

### 4.2 TraceIdFilter

```java
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 优先从请求头获取 traceId（上游传递），否则生成新的
        String traceId = exchange.getRequest().getHeaders()
            .getFirst(HeaderConstants.TRACE_ID);

        if (StrUtil.isBlank(traceId)) {
            traceId = IdUtil.fastSimpleUUID();  // Hutool 生成无横线 UUID
        }

        // 设置 MDC（用于日志输出）
        MDC.put("traceId", traceId);

        // 添加响应头，方便前端排查
        exchange.getResponse().getHeaders().add(HeaderConstants.TRACE_ID, traceId);

        return chain.filter(exchange).doFinally(signal -> MDC.clear());
    }

    @Override
    public int getOrder() { return -3; }
}
```

### 4.3 AuthGlobalFilter

```java
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    // 白名单路径（无需认证）
    private static final List<String> WHITE_LIST = List.of(
        "/api/auth/login",
        "/api/auth/register",
        "/api/auth/refresh",
        "/api/auth/captcha",
        "/v3/api-docs",
        "/doc.html",
        "/webjars",
        "/actuator/health"
    );

    // 内部服务调用路径（通过 X-Internal-Call 校验）
    private static final String INTERNAL_PREFIX = "/api/internal/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单放行
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 内部调用校验
        if (path.startsWith(INTERNAL_PREFIX)) {
            return validateInternalCall(exchange, chain);
        }

        // JWT 校验
        String token = extractToken(exchange);
        if (StrUtil.isBlank(token)) {
            return unauthorized(exchange, "缺少认证 Token");
        }

        try {
            Claims claims = JwtUtil.parseToken(token);
            // 将用户信息写入请求头，透传给下游
            exchange.getRequest().mutate()
                .header(HeaderConstants.USER_ID, claims.getSubject())
                .header(HeaderConstants.TENANT_ID, claims.get("tenantId", String.class))
                .header("X-User-Roles", String.join(",", claims.get("roles", List.class)));

            return chain.filter(exchange);
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Token 已过期");
        } catch (JwtException e) {
            return unauthorized(exchange, "无效的 Token");
        }
    }

    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> validateInternalCall(ServerWebExchange exchange, GatewayFilterChain chain) {
        String internalToken = exchange.getRequest().getHeaders()
            .getFirst("X-Internal-Call");
        // 内部调用 Token 由 Nacos 配置中心统一管理，各服务启动时读取
        if (!"{{internal-call-token}}".equals(internalToken)) {
            return unauthorized(exchange, "非法的内部调用");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = JSONUtil.toJsonStr(
            Map.of("code", 2001, "message", message, "data", null)
        );
        return exchange.getResponse()
            .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }

    @Override
    public int getOrder() { return 0; }
}
```

### 4.4 TenantContextFilter

```java
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 从请求头获取 tenant_id（AuthGlobalFilter 已注入）
        String tenantId = exchange.getRequest().getHeaders()
            .getFirst(HeaderConstants.TENANT_ID);

        if (StrUtil.isBlank(tenantId)) {
            // 白名单路径可能没有 tenant_id，使用系统租户
            tenantId = SystemConstants.SYSTEM_TENANT_ID;
        }

        // 确保 X-Tenant-Id 请求头存在，透传给下游服务
        exchange.getRequest().mutate()
            .header(HeaderConstants.TENANT_ID, tenantId);

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return 1; }
}
```

### 4.5 RequestLogFilter

```java
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        return chain.filter(exchange).doFinally(signal -> {
            long cost = System.currentTimeMillis() - start;
            HttpStatus status = HttpStatus.resolve(
                exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 500
            );

            log.info("[Gateway] {} {} → {} {}ms",
                method, path,
                status != null ? status.value() : 500,
                cost
            );
        });
    }

    @Override
    public int getOrder() { return -2; }
}
```

---

## 5. 限流策略

### 5.1 限流维度

| 维度 | 策略 | 说明 |
|------|------|------|
| 全局限流 | 10000 QPS | 平台整体入口保护 |
| 租户限流 | 按套餐配额 | 免费版 100 QPS，专业版 500 QPS，企业版 2000 QPS |
| 接口限流 | 敏感接口单独限制 | 登录接口 100 QPS/租户（防暴力破解） |
| IP 限流 | 单 IP 100 QPS | 防爬虫和恶意攻击 |

### 5.2 Sentinel 配置

```java
@Configuration
public class SentinelConfig {

    @PostConstruct
    public void initRules() {
        // 全局限流规则
        initGlobalRules();
        // 租户维度限流（从 Nacos 动态加载）
        initTenantRules();
        // 接口维度限流
        initApiRules();
    }

    private void initGlobalRules() {
        List<GatewayFlowRule> rules = new ArrayList<>();

        // 全局 QPS 限制
        rules.add(new GatewayFlowRule("global")
            .setCount(10000)       // 10000 QPS
            .setIntervalSec(1)
            .setBurst(20000)       // 突发流量缓冲
        );

        // 登录接口限流（防暴力破解）
        rules.add(new GatewayFlowRule("auth-login")
            .setCount(100)
            .setIntervalSec(60)    // 100 次/分钟
            .setParamItem(new GatewayParamFlowItem()
                .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_CLIENT_IP)
            )
        );

        GatewayRuleManager.loadRules(rules);
    }

    private void initTenantRules() {
        // 租户限流规则通过 Nacos 动态配置，支持热更新
        // 规则示例：
        // [
        //   {
        //     "resource": "tenant-api",
        //     "count": 500,
        //     "intervalSec": 1,
        //     "paramItem": {
        //       "parseStrategy": 3,  // PARAM_PARSE_STRATEGY_HEADER
        //       "fieldName": "X-Tenant-Id"
        //     }
        //   }
        // ]
    }
}
```

### 5.3 限流响应

被限流时返回 HTTP 429 Too Many Requests，响应体格式：

```json
{
  "code": 1005,
  "message": "请求过于频繁，请稍后重试",
  "data": null
}
```

---

## 6. CORS 配置

```java
@Configuration
public class CorsConfig implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 仅对跨域请求处理
        if (!CorsUtils.isCorsRequest(request)) {
            return chain.filter(exchange);
        }

        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();

        // 允许的来源（生产环境限制为具体域名）
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
        headers.add("Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-Tenant-Id, X-Trace-Id, X-Request-Id");
        headers.add("Access-Control-Expose-Headers",
            "X-Trace-Id, X-Request-Id");
        headers.add("Access-Control-Max-Age", "3600");

        // OPTIONS 预检请求直接返回 200
        if (request.getMethod() == HttpMethod.OPTIONS) {
            response.setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }

        return chain.filter(exchange);
    }
}
```

**设计决策**：生产环境部署时应将 `Access-Control-Allow-Origin` 从 `*` 改为具体域名列表，如 `https://admin.vhuan.com`。

---

## 7. 服务发现与负载均衡

Gateway 通过 Nacos 实现服务发现，`lb://` 前缀自动启用负载均衡：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
        group: DEFAULT_GROUP
    gateway:
      discovery:
        locator:
          enabled: true          # 启用基于服务发现的自动路由
          lower-case-service-id: true
    loadbalancer:
      nacos:
        enabled: true            # 与 Nacos 集成的负载均衡
```

**负载均衡策略**：默认使用 Round Robin，ai-engine-service 等关键服务使用 Least Connections（最小连接数）。

---

## 8. 配置管理

### 8.1 application.yml 核心配置

```yaml
server:
  port: 8080

spring:
  application:
    name: vhuan-gateway
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:vhuan}
        file-extension: yml
        shared-configs:
          - data-id: gateway-routes.yml     # 路由配置（动态刷新）
            group: DEFAULT_GROUP
            refresh: true
          - data-id: gateway-sentinel.yml   # 限流规则（动态刷新）
            group: DEFAULT_GROUP
            refresh: true

    gateway:
      httpclient:
        connect-timeout: 3000       # 连接超时 3s
        response-timeout: 30s       # 响应超时 30s
        pool:
          max-connections: 1000     # 最大连接池
          max-idle-time: 10s

    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD:localhost:8080}
      datasource:
        gateway-flow:
          nacos:
            server-addr: ${NACOS_ADDR:localhost:8848}
            namespace: ${NACOS_NAMESPACE:vhuan}
            data-id: gateway-sentinel.yml
            group: DEFAULT_GROUP
            rule-type: gw-flow

# JWT 配置
jwt:
  secret: ${JWT_SECRET}          # 通过环境变量注入，不硬编码
  expiration: 30                 # Access Token 有效期（分钟）
  refresh-expiration: 10080      # Refresh Token 有效期（分钟，7 天）

# 日志
logging:
  level:
    com.vhuan.gateway: INFO
    org.springframework.cloud.gateway: WARN
  pattern:
    console: '%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId}] %-5level [%thread] %logger{36} - %msg%n'
```

### 8.2 Nacos 动态路由配置

```
gateway-routes.yml（Nacos 配置中心）
├── 路由规则（支持热更新，无需重启 Gateway）
├── 新增/下线服务时只需修改 Nacos 配置
└── 配合 spring.cloud.gateway.routes 刷新机制
```

---

## 9. Knife4j 文档聚合

Gateway 作为统一入口，聚合所有微服务的 Swagger 文档，前端通过 `http://gateway:8080/doc.html` 访问所有服务的 API 文档：

```yaml
# Knife4j 网关聚合配置
knife4j:
  gateway:
    enabled: true
    strategy: discover      # 基于服务发现自动聚合
    discover:
      enabled: true
      version: openapi3
      excluded-services:
        - vhuan-ai-engine   # ai-engine 无 HTTP 接口，排除
```

---

## 10. Maven 依赖

```xml
<dependencies>
    <!-- Spring Cloud Gateway -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>

    <!-- Nacos 服务发现 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>

    <!-- Nacos 配置中心 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>

    <!-- Spring Cloud LoadBalancer -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>

    <!-- Sentinel Gateway 限流 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
    </dependency>

    <!-- Sentinel 数据源（Nacos 持久化） -->
    <dependency>
        <groupId>com.alibaba.csp</groupId>
        <artifactId>sentinel-datasource-nacos</artifactId>
    </dependency>

    <!-- Knife4j Gateway 文档聚合 -->
    <dependency>
        <groupId>com.github.xiaoymin</groupId>
        <artifactId>knife4j-gateway-spring-boot-starter</artifactId>
    </dependency>

    <!-- jjwt（JWT 解析，仅需 api 模块） -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- vhuan-common：HeaderConstants、SystemConstants -->
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-common</artifactId>
    </dependency>

    <!-- Hutool（IdUtil、StrUtil、JSONUtil） -->
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

    <!-- Actuator（健康检查 + 指标暴露） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
</dependencies>
```

**依赖说明**：
- Gateway 基于 **WebFlux**（Netty），不能引入 `spring-boot-starter-web`（Tomcat），否则冲突
- `vhuan-common` 中的 `spring-boot-starter-web` 依赖需排除，Gateway 仅引用常量类和工具类
- Sentinel 使用 `spring-cloud-alibaba-sentinel-gateway` 而非普通 Sentinel，适配 Gateway 的 WebFlux 模型

---

## 11. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 路由配置方式 | YAML vs Java DSL | YAML + Nacos 动态刷新 | 修改路由无需重启，Nacos 配置中心天然支持热更新 |
| 负载均衡 | Spring Cloud LoadBalancer | 内置 `lb://` 机制 | Spring Cloud Gateway 原生支持，无需额外引入 Ribbon |
| 限流方案 | Sentinel vs Redis 令牌桶 | Sentinel | 已集成，支持 Gateway 维度限流，Nacos 持久化规则 |
| 内部服务调用校验 | JWT vs 内部 Token | 内部 Token（`X-Internal-Call`） | 服务间调用无需模拟用户登录，内部 Token 更轻量 |
| Gateway 运行模式 | WebFlux（Netty）vs Tomcat | WebFlux（Netty） | Spring Cloud Gateway 基于 WebFlux，非阻塞 I/O 更适合同步转发场景 |
| 文档聚合 | 手动配置 vs 服务发现自动聚合 | 服务发现自动聚合 | 新增服务自动纳入文档，无需手动维护 |

---

## 12. 自检清单

- [ ] 路由表覆盖所有 10 个 HTTP 微服务（不含 ai-engine）
- [ ] 白名单路径（登录/注册/文档/健康检查）正确放行
- [ ] `AuthGlobalFilter` 正确解析 JWT 并注入 `X-User-Id`、`X-Tenant-Id`、`X-User-Roles`
- [ ] `TenantContextFilter` 确保每个请求都有 `X-Tenant-Id` 请求头
- [ ] `TraceIdFilter` 在过滤器链最前面执行，MDC 正确设置和清理
- [ ] Sentinel 限流规则覆盖全局、租户、接口、IP 四个维度
- [ ] 限流响应返回 HTTP 429 + 统一 JSON 格式
- [ ] CORS 配置正确，OPTIONS 预检请求直接返回 200
- [ ] 不引入 `spring-boot-starter-web`（Tomcat），避免与 WebFlux 冲突
- [ ] `vhuan-common` 依赖排除 `spring-boot-starter-web`，仅引用常量/工具类
- [ ] 路由配置支持 Nacos 动态刷新，无需重启
- [ ] Knife4j 文档聚合可通过 `/doc.html` 访问
- [ ] 健康检查 `/actuator/health` 在白名单中

---

> **下一步**：本设计确认后，进入第二阶段 `vhuan-auth` 详细设计。