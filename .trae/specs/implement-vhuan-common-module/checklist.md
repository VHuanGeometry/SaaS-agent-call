# Checklist

- [x] 父 `pom.xml` 的 `<modules>` 中包含 `vhuan-common`
- [x] 父 `pom.xml` 的 `dependencyManagement` 中 `hutool-all` 版本为 5.8.29
- [x] `vhuan-common/pom.xml` 声明了 spring-boot-starter-web、spring-boot-starter-validation、graceful-response、hutool-all、lombok、mapstruct、slf4j-api
- [x] `vhuan-common/pom.xml` 不引入 MyBatis-Flex、Nacos、Redis、Kafka 依赖
- [x] `BizErrorCode` 为普通枚举（graceful-response 3.4.0 无 GracefulResponseEnumInterface，改为 code/msg 载体），公共错误码 1000-1999（SUCCESS=0, PARAM_INVALID=1001, RESOURCE_NOT_FOUND=1002, OPERATION_FAILED=1003, INTERNAL_ERROR=1004）
- [x] `BizException` 继承 `GracefulResponseException`（动态传入 code/msg，替代静态 @ExceptionMapper），提供 `(BizErrorCode)` 和 `(BizErrorCode, String detail)` 两个构造方法
- [x] 不存在 `GlobalExceptionHandler` 类（注释提及"无需自建"不算类定义）
- [x] `TenantContext` 为 Record，字段为 tenantId、tenantName、planCode、userId、userName，包含 SYSTEM 静态常量
- [x] `TenantContextHolder` 使用 `ScopedValue<TenantContext>`（java.lang 包，非 ThreadLocal），提供 get()、getTenantId()、isSystem()、getScopedValue() 方法
- [x] `BaseEntity` 包含 id、createdAt、updatedAt、createdBy、updatedBy、deleted 字段，不含 MyBatis-Flex 注解
- [x] `BaseVO` 包含 id、createdAt、updatedAt 字段
- [x] `HeaderConstants` 包含 TENANT_ID="X-Tenant-Id"、TRACE_ID="X-Trace-Id"、USER_ID="X-User-Id"、REQUEST_ID="X-Request-Id"
- [x] `SystemConstants` 包含 SYSTEM_TENANT_ID="system"、DEFAULT_PASSWORD="Vhuan@2024"、TOKEN_EXPIRE_MINUTES=30、REFRESH_TOKEN_EXPIRE_DAYS=7
- [x] `CommonAutoConfiguration` 注册 Snowflake Bean（worker-id/datacenter-id 默认 1），使用 `@ComponentScan("com.vhuan.common")`
- [x] `JacksonConfig` 配置日期格式 yyyy-MM-dd HH:mm:ss（通过 JavaTimeModule 注册 LocalDateTimeSerializer/Deserializer）、时区 Asia/Shanghai、不序列化 null、Long 超 JS 安全范围转 String
- [x] 存在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件并注册 `CommonAutoConfiguration`
- [x] 不存在自定义工具类（util/ 包），所有工具需求使用 Hutool
- [x] 不存在自定义 PageQuery/PageResult，分页由业务模块使用 MyBatis-Flex Page
- [x] `mvn -pl vhuan-common -am compile` 编译通过（exit code 0）

# 验证偏差说明
- **第 5、6 项**：design 文档描述 BizErrorCode 实现 GracefulResponseEnumInterface、BizException 使用 @ExceptionMapper。实际 graceful-response 3.4.0-boot3 无此接口，且 @ExceptionMapper 的 code 为静态值无法承载动态错误码。实现调整为：BizErrorCode 为普通枚举，BizException 继承 GracefulResponseException(code, msg) 动态传入错误码。功能等价，由框架 GlobalExceptionAdvice 自动捕获转换。
- **ScopedValue 预览特性**：JDK 21 中 ScopedValue 为预览 API，pom.xml 已配置 `--enable-preview` 编译参数与 surefire 运行时参数，已标记 TODO（JDK 24 转正后移除）。
