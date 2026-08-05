# vhuan-common 公共模块实现 Spec

## Why

`vhuan-common` 是所有微服务模块的编译依赖与基础设施底座，必须先行稳定。本 spec 基于 `design/vhuan-common.md` 详细设计文档，将设计落地为可编译、可复用的 Maven 模块代码，为后续 `vhuan-proto`、`vhuan-gateway`、`vhuan-auth` 等模块提供统一异常体系、租户上下文、基础实体、常量与公共自动配置。

设计原则：
- 零业务逻辑，仅提供基础设施抽象
- 优先复用成熟开源项目，禁止重复造轮子（Graceful Response、Hutool、MyBatis-Flex）
- 所有类放在 `com.vhuan.common` 包下

## What Changes

### 新增模块
- 新增 `vhuan-common` Maven 子模块（在父 `pom.xml` 注册 `<module>vhuan-common</module>`）
- 在父 `pom.xml` 的 `dependencyManagement` 中新增 `hutool-all` 依赖版本管理（5.8.29）
- 在父 `pom.xml` 的 `dependencyManagement` 中新增 `slf4j-api` 版本管理（如未显式管理则由 Spring Boot BOM 接管）

### 新增代码包结构（`com.vhuan.common`）
- `exception/BizErrorCode.java` — 业务错误码枚举，实现 `GracefulResponseEnumInterface`
- `exception/BizException.java` — 业务异常，使用 `@ExceptionMapper` 声明映射
- `context/TenantContext.java` — 租户上下文 Record（不可变）
- `context/TenantContextHolder.java` — 基于 Scoped Values 的上下文持有者
- `entity/BaseEntity.java` — 审计字段基类（不含 ORM 注解）
- `entity/BaseVO.java` — 视图对象基类
- `constant/HeaderConstants.java` — 请求头常量
- `constant/SystemConstants.java` — 系统常量
- `config/CommonAutoConfiguration.java` — 公共自动配置（注册 Snowflake Bean、ComponentScan）
- `config/JacksonConfig.java` — Jackson 序列化配置
- `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — 注册自动配置类

### 关键设计决策（落地约束）
- 不写 `GlobalExceptionHandler`，由 Graceful Response 的 `@ExceptionMapper` 接管
- 不在公共模块自定义工具类，所有通用工具需求直接使用 Hutool（`IdUtil`、`DateUtil`、`StrUtil`、`Assert`、`CollUtil` 等）
- 不自定义 `PageQuery`/`PageResult`，分页直接复用 MyBatis-Flex 的 `Page<T>`（在业务模块中引入）
- `BaseEntity` 不含 MyBatis-Flex 注解，由业务模块子类自行添加
- `vhuan-common` 不直接依赖 MyBatis-Flex、Nacos、Redis、Kafka
- `TenantContextHolder` 使用 JDK 21 Scoped Values，非 ThreadLocal

## Impact

- **Affected specs**: 无（首个 spec）
- **Affected code**:
  - `pom.xml`（父 POM）：注册 `vhuan-common` 子模块、新增 `hutool-all` 版本管理
  - 新建 `vhuan-common/pom.xml`
  - 新建 `vhuan-common/src/main/java/com/vhuan/common/**` 下全部 Java 类
  - 新建 `vhuan-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## ADDED Requirements

### Requirement: Maven 模块化与依赖管理
系统 SHALL 在父 POM 中注册 `vhuan-common` 子模块，并在 `dependencyManagement` 中统一管理 `hutool-all` 版本（5.8.29）。`vhuan-common/pom.xml` SHALL 声明 `spring-boot-starter-web`、`spring-boot-starter-validation`、`graceful-response`、`hutool-all`、`lombok`、`mapstruct`、`slf4j-api` 依赖，且不引入 MyBatis-Flex、Nacos、Redis、Kafka 等中间件依赖。

#### Scenario: 父 POM 注册子模块
- **WHEN** 执行 `mvn validate`
- **THEN** 父 POM 的 `<modules>` 中包含 `vhuan-common`，且 `hutool-all` 在 `dependencyManagement` 中版本为 5.8.29

#### Scenario: 模块编译
- **WHEN** 执行 `mvn -pl vhuan-common compile`
- **THEN** 编译通过，无错误

### Requirement: 异常体系（基于 Graceful Response）
系统 SHALL 提供 `BizErrorCode` 枚举，实现 `GracefulResponseEnumInterface`，按模块划分错误码区间（公共 1000-1999）。系统 SHALL 提供 `BizException`，使用 `@ExceptionMapper` 注解声明映射关系。系统 SHALL NOT 实现 `GlobalExceptionHandler`，由 Graceful Response 接管参数校验异常（→1001）与兜底异常（→1004）。

#### Scenario: 抛出业务异常
- **WHEN** 业务代码执行 `throw new BizException(BizErrorCode.PARAM_INVALID, "手机号格式错误")`
- **THEN** Graceful Response 自动将其映射为 `{ "code": 1001, "msg": "参数校验失败：手机号格式错误", "data": null }`

#### Scenario: 抛出无详情业务异常
- **WHEN** 业务代码执行 `throw new BizException(BizErrorCode.RESOURCE_NOT_FOUND)`
- **THEN** 响应 msg 为 "资源不存在"

### Requirement: 租户上下文（Scoped Values）
系统 SHALL 提供 `TenantContext` Record（不可变），包含 tenantId、tenantName、planCode、userId、userName 字段，并提供 `SYSTEM` 静态常量。系统 SHALL 提供 `TenantContextHolder`，使用 `ScopedValue<TenantContext>` 存储上下文，提供 `get()`、`getTenantId()`、`isSystem()`、`getScopedValue()` 方法。

#### Scenario: 获取上下文
- **WHEN** 在 `ScopedValue.where(CONTEXT, ctx).run(() -> ...)` 代码块内调用 `TenantContextHolder.get()`
- **THEN** 返回传入的 `TenantContext` 实例

#### Scenario: 系统上下文
- **WHEN** 调用 `TenantContext.SYSTEM.tenantId()`
- **THEN** 返回 "system"

### Requirement: 基础实体
系统 SHALL 提供 `BaseEntity`，包含 id、createdAt、updatedAt、createdBy、updatedBy、deleted 字段，且不含任何 MyBatis-Flex 注解。系统 SHALL 提供 `BaseVO`，包含 id、createdAt、updatedAt 字段。

#### Scenario: BaseEntity 字段
- **WHEN** 子类继承 `BaseEntity`
- **THEN** 可访问 id（String）、createdAt（LocalDateTime）、updatedAt（LocalDateTime）、createdBy（String）、updatedBy（String）、deleted（Integer）字段

### Requirement: 常量
系统 SHALL 提供 `HeaderConstants`（TENANT_ID、TRACE_ID、USER_ID、REQUEST_ID）与 `SystemConstants`（SYSTEM_TENANT_ID、DEFAULT_PASSWORD、TOKEN_EXPIRE_MINUTES、REFRESH_TOKEN_EXPIRE_DAYS）。

#### Scenario: 请求头常量
- **WHEN** 引用 `HeaderConstants.TENANT_ID`
- **THEN** 值为 "X-Tenant-Id"

### Requirement: 公共自动配置
系统 SHALL 提供 `CommonAutoConfiguration`，从配置读取 `snowflake.worker-id`（默认 1）和 `snowflake.datacenter-id`（默认 1），通过 Hutool `IdUtil.getSnowflake()` 注册 `Snowflake` Bean，并通过 `@ComponentScan("com.vhuan.common")` 扫描公共组件。系统 SHALL 提供 `JacksonConfig`，配置日期格式 `yyyy-MM-dd HH:mm:ss`、时区 `Asia/Shanghai`、不序列化 null 字段、Long 超 JS 安全整数范围转 String。系统 SHALL 通过 Spring Boot AutoConfiguration 机制注册 `CommonAutoConfiguration`。

#### Scenario: Snowflake Bean 注入
- **WHEN** 业务模块引入 `vhuan-common` 并配置 `snowflake.worker-id=2`
- **THEN** 容器中存在 `Snowflake` Bean，`nextIdStr()` 返回基于 worker-id=2 生成的 ID 字符串

#### Scenario: Jackson 序列化
- **WHEN** 序列化包含 null 字段的对象
- **THEN** 输出的 JSON 中不包含该 null 字段
- **WHEN** 序列化 `LocalDateTime` 字段
- **THEN** 输出格式为 `yyyy-MM-dd HH:mm:ss`
