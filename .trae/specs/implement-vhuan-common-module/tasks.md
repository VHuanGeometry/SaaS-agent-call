# Tasks

- [x] Task 1: 父 POM 注册 vhuan-common 子模块与依赖管理
  - [x] SubTask 1.1: 在父 `pom.xml` 的 `<modules>` 中新增 `vhuan-common`
  - [x] SubTask 1.2: 在父 `pom.xml` 的 `<properties>` 中新增 `hutool.version=5.8.29`
  - [x] SubTask 1.3: 在父 `pom.xml` 的 `dependencyManagement` 中新增 `hutool-all` 依赖版本管理
- [x] Task 2: 创建 vhuan-common 模块 pom.xml
  - [x] SubTask 2.1: 创建 `vhuan-common/pom.xml`，声明父 POM、artifactId=vhuan-common、packaging=jar
  - [x] SubTask 2.2: 声明依赖：spring-boot-starter-web、spring-boot-starter-validation、graceful-response、hutool-all、lombok、mapstruct、slf4j-api
  - [x] SubTask 2.3: 不引入 MyBatis-Flex、Nacos、Redis、Kafka 等中间件依赖
- [x] Task 3: 实现异常体系
  - [x] SubTask 3.1: 创建 `BizErrorCode` 枚举，包含公共错误码 1000-1999（SUCCESS=0, PARAM_INVALID=1001, RESOURCE_NOT_FOUND=1002, OPERATION_FAILED=1003, INTERNAL_ERROR=1004）。注：graceful-response 3.4.0 无 GracefulResponseEnumInterface，改为普通枚举作为 code/msg 载体
  - [x] SubTask 3.2: 创建 `BizException`，继承 GracefulResponseException（动态传入 code/msg），提供 `BizException(BizErrorCode)` 和 `BizException(BizErrorCode, String detail)` 两个构造方法
- [x] Task 4: 实现租户上下文
  - [x] SubTask 4.1: 创建 `TenantContext` Record，包含 tenantId、tenantName、planCode、userId、userName 字段及 SYSTEM 静态常量
  - [x] SubTask 4.2: 创建 `TenantContextHolder`，使用 `ScopedValue<TenantContext>`（java.lang 包），提供 get()、getTenantId()、isSystem()、getScopedValue() 方法。启用 --enable-preview（JDK 21 预览特性）
- [x] Task 5: 实现基础实体
  - [x] SubTask 5.1: 创建 `BaseEntity`，包含 id、createdAt、updatedAt、createdBy、updatedBy、deleted 字段，使用 Lombok 注解，不含 MyBatis-Flex 注解
  - [x] SubTask 5.2: 创建 `BaseVO`，包含 id、createdAt、updatedAt 字段，使用 Lombok 注解
- [x] Task 6: 实现常量类
  - [x] SubTask 6.1: 创建 `HeaderConstants`（TENANT_ID、TRACE_ID、USER_ID、REQUEST_ID），final class + private 构造方法
  - [x] SubTask 6.2: 创建 `SystemConstants`（SYSTEM_TENANT_ID、DEFAULT_PASSWORD、TOKEN_EXPIRE_MINUTES、REFRESH_TOKEN_EXPIRE_DAYS），final class + private 构造方法
- [x] Task 7: 实现公共自动配置
  - [x] SubTask 7.1: 创建 `CommonAutoConfiguration`，使用 `@Configuration` + `@ComponentScan("com.vhuan.common")`，注册 `Snowflake` Bean（从配置读取 worker-id/datacenter-id，默认 1）
  - [x] SubTask 7.2: 创建 `JacksonConfig`，配置 ObjectMapper：日期格式 yyyy-MM-dd HH:mm:ss、时区 Asia/Shanghai、不序列化 null、Long 转 String（超 JS 安全范围）、LocalDateTime 支持
  - [x] SubTask 7.3: 创建 `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件，注册 `CommonAutoConfiguration`
- [x] Task 8: 编译验证
  - [x] SubTask 8.1: 执行 `mvn -pl vhuan-common -am compile` 验证编译通过
  - [x] SubTask 8.2: 自检：无业务逻辑、无中间件依赖、无自定义工具类、无 GlobalExceptionHandler

# Task Dependencies
- Task 2 依赖 Task 1（父 POM 注册）
- Task 3-7 依赖 Task 2（模块 pom.xml 存在）
- Task 3-7 之间无依赖，可并行
- Task 8 依赖 Task 3-7 全部完成

# 实现偏差记录（与原 design 文档的差异）
1. **BizErrorCode 不实现 GracefulResponseEnumInterface**：graceful-response 3.4.0-boot3 无此接口，改为普通枚举作为 code/msg 载体。
2. **BizException 继承 GracefulResponseException 而非使用 @ExceptionMapper**：@ExceptionMapper 的 code 为静态值，无法承载动态错误码。继承 GracefulResponseException(code, msg) 构造方法可动态传入 code，由框架 GlobalExceptionAdvice 自动捕获转换。
3. **ScopedValue 启用 --enable-preview**：ScopedValue 在 JDK 21 为预览特性，pom.xml 配置 `--enable-preview` 编译参数，已标记 TODO（JDK 24 转正后移除）。
4. **JacksonConfig 不使用 dateFormat(String)**：Jackson2ObjectMapperBuilder.dateFormat() 仅接受 DateFormat 类型，改用 JavaTimeModule 注册 LocalDateTimeSerializer/Deserializer。
