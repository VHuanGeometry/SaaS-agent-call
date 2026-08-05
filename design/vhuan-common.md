# vhuan-common 详细设计

> **模块**: vhuan-common（公共模块）  
> **阶段**: 第一阶段 — 基础设施  
> **版本**: v1.0.0  
> **日期**: 2026-08-05  
> **状态**: 设计中

---

## 1. 设计目标

为所有微服务模块提供统一的公共基础设施。该模块是所有模块的编译依赖，必须先稳定。

**设计原则**：
- 零业务逻辑，仅提供基础设施抽象
- **优先复用成熟开源项目，禁止重复造轮子**（详见 AGENTS.md）
- 所有类放在 `com.vhuan.common` 包下，子包按功能划分

**复用清单**：

| 能力 | 复用的开源项目 | 说明 |
|------|---------------|------|
| 统一响应体 + Controller 自动包装 | **Graceful Response** (3.4.0-boot3) | 已集成，`@ResponseBody` 自动包装，支持自定义异常映射 |
| 工具类（日期、字符串、断言、集合等） | **Hutool** (5.8.x) | 国产 Java 工具类库，覆盖 90% 的通用工具需求 |
| 分页模型 | **MyBatis-Flex** (1.9.5) | 已集成，`Page` 类可直接作为分页请求/响应模型 |
| 雪花 ID 生成 | **Hutool** `IdUtil.getSnowflake()` | 内置 workerId 自动分配，无需自己实现 |

---

## 2. 包结构

```
com.vhuan.common
├── exception                   # 异常体系（基于 Graceful Response）
│   ├── BizException.java       # 业务异常（@ExceptionMapper 注解）
│   └── BizErrorCode.java       # 业务错误码枚举（实现 GracefulResponseEnumInterface）
│
├── context                     # 租户上下文
│   ├── TenantContext.java      # 上下文数据结构 (Record)
│   └── TenantContextHolder.java # 上下文持有者（Scoped Values 实现）
│
├── entity                      # 基础实体
│   ├── BaseEntity.java         # 审计字段基类
│   └── BaseVO.java             # 视图对象基类
│
├── constant                    # 常量
│   ├── HeaderConstants.java    # 请求头常量
│   └── SystemConstants.java    # 系统常量
│
└── config                      # 公共自动配置
    ├── CommonAutoConfiguration.java  # 公共模块自动配置（注册 Hutool Snowflake、Jackson 配置）
    └── JacksonConfig.java            # Jackson 序列化配置
```

**对比原方案，删除的包**：

| 删除的包 | 替代方案 |
|----------|----------|
| `response/`（Result、PageResult、ResultCode） | Graceful Response 的 `Response` + MyBatis-Flex 的 `Page` |
| `util/`（SnowflakeIdGenerator、DateTimeUtils、AssertUtils） | Hutool 的 `IdUtil`、`DateUtil`、`Assert`、`StrUtil` |
| `dto/PageQuery` | MyBatis-Flex 的 `Page` + `QueryWrapper` |
| `dto/IdRequest` | 各模块按需定义，不放入公共模块 |

---

## 3. 异常体系（基于 Graceful Response）

### 3.1 设计思路

Graceful Response 提供了 `@ExceptionMapper` 注解，将自定义异常映射为统一响应。本模块只需定义：
- `BizErrorCode` 枚举，实现 `GracefulResponseEnumInterface`
- `BizException`，使用 `@ExceptionMapper` 声明映射关系

Graceful Response 会自动处理 Controller 层的包装和异常转换，无需再写 `GlobalExceptionHandler`。

### 3.2 BizErrorCode 枚举

实现 Graceful Response 的 `com.feiniaojin.gracefulresponse.api.GracefulResponseEnumInterface` 接口：

```java
public enum BizErrorCode implements GracefulResponseEnumInterface {

    // ========== 公共错误码 1000-1999 ==========
    SUCCESS(0, "操作成功"),
    PARAM_INVALID(1001, "参数校验失败"),
    RESOURCE_NOT_FOUND(1002, "资源不存在"),
    OPERATION_FAILED(1003, "操作失败"),
    INTERNAL_ERROR(1004, "系统内部错误"),

    // ========== auth 2000-2999 ==========
    // （在 vhuan-auth 模块中扩展）

    // ========== tenant 3000-3999 ==========
    // （在 vhuan-tenant 模块中扩展）

    // ... 各模块按区间扩展

    ;

    private final int code;
    private final String msg;

    BizErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() { return code; }

    @Override
    public String getMsg() { return msg; }
}
```

**扩展方式**：各业务模块在自己的枚举中实现同一接口，错误码按区间分配，避免冲突。

### 3.3 BizException

```java
@ExceptionMapper(code = "1004", msg = "系统内部错误", httpStatus = HttpStatus.INTERNAL_SERVER_ERROR)
public class BizException extends RuntimeException {

    private final BizErrorCode errorCode;

    public BizException(BizErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public BizException(BizErrorCode errorCode, String detail) {
        super(errorCode.getMsg() + "：" + detail);
        this.errorCode = errorCode;
    }

    public BizErrorCode getErrorCode() { return errorCode; }
}
```

**使用方式**：

```java
// 抛出业务异常
throw new BizException(BizErrorCode.PARAM_INVALID, "手机号格式错误");
throw new BizException(BizErrorCode.RESOURCE_NOT_FOUND);

// Graceful Response 自动将异常映射为：
// { "code": 1001, "msg": "参数校验失败：手机号格式错误", "data": null }
```

**不需要 GlobalExceptionHandler**：Graceful Response 的 `@ExceptionMapper` 已覆盖参数校验异常（`MethodArgumentNotValidException` → `1001`）和兜底异常（`Exception` → `1004`），无需再写全局异常处理器。

### 3.4 错误码区间分配

| 模块 | 错误码区间 | 说明 |
|------|------------|------|
| 公共 | 1000-1999 | 参数校验、通用错误 |
| auth | 2000-2999 | 认证授权 |
| tenant | 3000-3999 | 租户管理 |
| agent | 4000-4999 | Agent 配置 |
| campaign | 5000-5999 | 外呼任务 |
| call | 6000-6999 | 通话管理 |
| ai-engine | 7000-7999 | AI 引擎 |
| contact | 8000-8999 | 客户线索 |
| analytics | 9000-9999 | 数据分析 |
| notification | 10000-10999 | 通知 |
| sip | 11000-11999 | SIP 连接器 |

---

## 4. 租户上下文

### 4.1 设计要点

- 使用 **JDK 21 Scoped Values** 替代 ThreadLocal，解决虚拟线程场景下的上下文传播问题
- `TenantContext` 为不可变 Record，线程安全
- 提供 `TenantContextHolder` 静态方法供全链路使用

### 4.2 TenantContext（Record）

```java
public record TenantContext(
    String tenantId,      // 租户 ID
    String tenantName,    // 租户名称（冗余，减少查询）
    String planCode,      // 套餐编码（用于配额校验）
    Long userId,          // 当前用户 ID（可为 null，如定时任务）
    String userName       // 当前用户名（可为 null）
) {
    // 系统级上下文（定时任务、系统回调）
    public static final TenantContext SYSTEM = new TenantContext(
        "system", "系统", "SYSTEM", null, null
    );
}
```

### 4.3 TenantContextHolder

```java
public final class TenantContextHolder {

    private static final ScopedValue<TenantContext> CONTEXT = ScopedValue.newInstance();

    // 获取 ScopedValue 实例（供 Filter/拦截器使用）
    public static ScopedValue<TenantContext> getScopedValue() { return CONTEXT; }

    // 便捷获取方法
    public static TenantContext get() { return CONTEXT.get(); }
    public static String getTenantId() { return CONTEXT.get().tenantId(); }
    public static boolean isSystem() { return "system".equals(getTenantId()); }
}
```

### 4.4 上下文传播链路

```
HTTP 请求 → Gateway（解析 JWT，注入 X-Tenant-Id）
         → 各微服务 Filter（读取 X-Tenant-Id，查询租户信息）
         → ScopedValue.where(CONTEXT, tenantContext).run(() -> {
               // 业务逻辑，内部任意位置可调用 TenantContextHolder.get()
           })

Kafka 消息 → 消费者（从消息体解析 tenantId）
          → ScopedValue.where(CONTEXT, context).run(() -> { ... })

gRPC 请求 → 拦截器（从 Metadata 读取 tenant-id）
         → ScopedValue.where(CONTEXT, context).run(() -> { ... })
```

---

## 5. 基础实体

### 5.1 BaseEntity

```java
public class BaseEntity {
    // 主键 — 由 Hutool IdUtil.getSnowflake() 生成
    // 在 MyBatis-Flex 的 @Id 注解中配合 InsertListener 自动填充
    private String id;

    // 审计字段 — 由 MyBatis-Flex 的 @Column(onInsertValue = "now()") 自动填充
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    // 逻辑删除 — MyBatis-Flex @Column(isLogicDelete = true) 处理
    // 默认值 0 = 正常，1 = 已删除
    private Integer deleted;
}
```

**设计决策**：`BaseEntity` 不放置 MyBatis-Flex 注解，注解由各业务模块的子类自行添加。这样 `vhuan-common` 不直接依赖 MyBatis-Flex，但各模块通过继承 + 注解即可使用。

### 5.2 BaseVO

```java
public class BaseVO {
    private String id;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

视图对象基类，与 `BaseEntity` 对应，不含 `deleted` 等内部字段。

---

## 6. 分页模型（复用 MyBatis-Flex）

**无需自建 PageQuery/PageResult**。

MyBatis-Flex 的 `com.mybatisflex.core.paginate.Page<T>` 可直接作为分页请求和响应：

```java
// Controller 层接收分页请求
@GetMapping("/list")
public Page<SomeVO> list(Page<SomeVO> page, @RequestParam String keyword) {
    // Page 对象自动绑定 pageNumber、pageSize 参数
    return service.page(page, keyword);
}

// Service 层使用 MyBatis-Flex 的 QueryWrapper
Page<SomeVO> result = mapper.paginate(page, queryWrapper);
```

**优点**：
- `Page<T>` 同时承载分页请求参数和响应结果，无需手动转换
- 与 MyBatis-Flex 的 `Mapper.paginate()` 方法无缝衔接
- 字段命名兼容 Spring Data 的分页参数规范（`pageNumber`、`pageSize`）

---

## 7. 常量

### 7.1 HeaderConstants

```java
public final class HeaderConstants {
    public static final String TENANT_ID = "X-Tenant-Id";      // 租户 ID
    public static final String TRACE_ID = "X-Trace-Id";         // 链路追踪 ID
    public static final String USER_ID = "X-User-Id";           // 当前用户 ID
    public static final String REQUEST_ID = "X-Request-Id";     // 请求 ID
}
```

### 7.2 SystemConstants

```java
public final class SystemConstants {
    public static final String SYSTEM_TENANT_ID = "system";     // 系统租户 ID
    public static final String DEFAULT_PASSWORD = "Vhuan@2024"; // 新用户默认密码
    public static final long TOKEN_EXPIRE_MINUTES = 30;         // Access Token 有效期
    public static final long REFRESH_TOKEN_EXPIRE_DAYS = 7;     // Refresh Token 有效期
}
```

---

## 8. 工具类（复用 Hutool）

**不在公共模块中自定义工具类**。所有通用工具需求直接使用 Hutool：

| 需求 | Hutool 工具类 | 示例 |
|------|--------------|------|
| 雪花 ID 生成 | `IdUtil.getSnowflake(workerId, datacenterId)` | `IdUtil.getSnowflake(1, 1).nextIdStr()` |
| 日期格式化 | `DateUtil` | `DateUtil.format(date, "yyyy-MM-dd HH:mm:ss")` |
| 日期解析 | `DateUtil` | `DateUtil.parse("2024-01-01", "yyyy-MM-dd")` |
| 日期范围 | `DateUtil` | `DateUtil.beginOfDay(date)`、`DateUtil.endOfMonth(date)` |
| 字符串判空 | `StrUtil` | `StrUtil.isBlank(str)`、`StrUtil.format("{} {}", a, b)` |
| 集合判空 | `CollUtil` | `CollUtil.isEmpty(list)` |
| 断言 | `Assert` | `Assert.notNull(obj, "对象不能为空")` |
| 对象拷贝 | `BeanUtil` | `BeanUtil.copyProperties(source, target)` |
| JSON 序列化 | `JSONUtil` | `JSONUtil.toJsonStr(obj)` |
| MD5/SHA | `DigestUtil` | `DigestUtil.md5Hex(str)` |

**Snowflake 配置**在 `CommonAutoConfiguration` 中注册 Bean：

```java
@Configuration
public class CommonAutoConfiguration {

    @Value("${snowflake.worker-id:1}")
    private long workerId;

    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId;

    @Bean
    public Snowflake snowflake() {
        return IdUtil.getSnowflake(workerId, datacenterId);
    }
}
```

K8s 部署时通过环境变量注入 `snowflake.worker-id`（`StatefulSet` 的 `$(POD_INDEX)`），`datacenter-id` 按可用区分配。

---

## 9. 公共自动配置

### 9.1 CommonAutoConfiguration

```java
@Configuration
@ComponentScan(basePackages = "com.vhuan.common")
public class CommonAutoConfiguration {
    // 注册 Snowflake Bean（Hutool）
    // 注册 Jackson 配置
}
```

各业务模块通过 `@Import(CommonAutoConfiguration.class)` 或 Spring Boot 自动配置机制加载。

### 9.2 JacksonConfig

```java
@Configuration
public class JacksonConfig {
    // 配置 ObjectMapper：
    // - 日期格式：yyyy-MM-dd HH:mm:ss
    // - 时区：Asia/Shanghai
    // - null 值序列化：不输出 null 字段
    // - Long 序列化：超过 JS 安全整数范围时转为 String
    // - 枚举：使用 code 值序列化
}
```

---

## 10. Maven 依赖

```xml
<dependencies>
    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- 参数校验 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Graceful Response：统一响应体 + 异常映射 -->
    <dependency>
        <groupId>com.feiniaojin</groupId>
        <artifactId>graceful-response</artifactId>
    </dependency>

    <!-- Hutool：通用工具类库 -->
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
        <version>5.8.29</version>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- MapStruct -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>

    <!-- SLF4J -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
    </dependency>
</dependencies>
```

**依赖决策**：
- `vhuan-common` 不直接依赖 MyBatis-Flex。`BaseEntity` 中的逻辑删除注解由各业务模块引入 MyBatis-Flex 后，在子类中自行添加
- 新增 `hutool-all`，覆盖所有通用工具需求，不再自建 `util/` 包
- Graceful Response 已在 `pom.xml` 的 `dependencyManagement` 中统一管理版本

---

## 11. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 统一响应包装 | 自研 vs Graceful Response | **Graceful Response** | 项目已集成，提供 `@ExceptionMapper` 异常映射，无需自建 GlobalExceptionHandler |
| 工具类 | 自研 vs Hutool | **Hutool** | 国产工具类库，覆盖日期/字符串/断言/集合/JSON/加密等，避免重复造轮子 |
| 雪花 ID | 自研 vs Hutool IdUtil | **Hutool IdUtil** | Hutool 内置雪花算法，支持 workerId 自动分配，比自研更稳定 |
| 分页模型 | 自研 PageResult vs MyBatis-Flex Page | **MyBatis-Flex Page** | 与 ORM 无缝衔接，减少手动转换 |
| 租户上下文传递 | ThreadLocal vs Scoped Values | **Scoped Values** | 虚拟线程场景下 ThreadLocal 泄漏风险高，Scoped Values 生命周期受结构化并发约束 |
| 全局异常处理 | 自研 vs Graceful Response @ExceptionMapper | **Graceful Response** | 声明式异常映射，无需写 GlobalExceptionHandler 代码 |

---

## 12. 自检清单

- [ ] BizErrorCode 实现 `GracefulResponseEnumInterface`，错误码按区间无冲突
- [ ] BizException 使用 `@ExceptionMapper` 注解，无需自建 GlobalExceptionHandler
- [ ] TenantContext 使用 Record，不可变
- [ ] TenantContextHolder 使用 Scoped Values，非 ThreadLocal
- [ ] 雪花 ID 使用 Hutool `IdUtil.getSnowflake()`，通过 CommonAutoConfiguration 注册 Bean
- [ ] 分页使用 MyBatis-Flex `Page<T>`，不自定义 PageQuery/PageResult
- [ ] 不在 `vhuan-common` 中自定义工具类，全部使用 Hutool
- [ ] BaseEntity 不含 MyBatis-Flex 注解（由业务模块子类自行添加）
- [ ] Jackson 配置正确处理 Long 精度、日期格式、null 值
- [ ] 无业务逻辑，无外部中间件依赖（Nacos/Redis/Kafka 等）
- [ ] `hutool-all` 加入 `dependencyManagement` 统一管理版本

---

> **下一步**：本设计确认后，进入 `vhuan-proto` 详细设计。