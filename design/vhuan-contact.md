# vhuan-contact 详细设计

> **模块**: vhuan-contact（客户与线索服务）  
> **阶段**: 第三阶段 — 旁路支撑  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供客户与线索数据的全生命周期管理：名单管理、线索管理、号码导入导出、去重、黑名单管理。为核心业务链路提供外呼所需的联系人数据源。

**职责边界**：
- 名单管理：创建名单、导入（Excel/CSV/API）、导出、标签管理
- 线索管理：线索 CRUD、状态流转、号码去重
- 黑名单：号码黑名单（全局 + 租户级别）、合规勿扰时段
- 号码校验：导入时号码格式校验、黑名单过滤

**非职责**：
- 不发起外呼调度（由 `vhuan-campaign` 负责），通过 `@HttpExchange` 提供名单和号码数据
- 不处理通话结果更新（由 `vhuan-campaign` 通过 Kafka 消费），但提供号码状态回写接口
- 不管理用户与权限（由 `vhuan-auth` 负责）

**与 campaign 的协作**：
- campaign 创建任务时调用 contact-service 的 `ContactApi` 拉取名单号码
- campaign 通过 Kafka 消费通话结果后，调用 contact-service 回写号码状态（如已接通、意向等级）
- contact-service 不消费通话事件，由 campaign 转写

---

## 2. 模块结构

```
vhuan-contact/
├── pom.xml
├── src/main/java/com/vhuan/contact/
│   ├── ContactApplication.java
│   │
│   ├── controller/
│   │   ├── ContactController.java              # 线索管理
│   │   ├── ContactListController.java          # 名单管理
│   │   ├── ImportController.java               # 导入管理
│   │   └── BlacklistController.java            # 黑名单管理
│   │
│   ├── service/
│   │   ├── ContactService.java                 # 线索核心逻辑
│   │   ├── ContactListService.java             # 名单管理
│   │   ├── ImportService.java                  # 导入导出
│   │   ├── DeduplicationService.java           # 号码去重
│   │   ├── BlacklistService.java               # 黑名单管理
│   │   ├── ContactNumberValidator.java         # 号码校验
│   │   └── impl/
│   │       ├── ContactServiceImpl.java
│   │       ├── ContactListServiceImpl.java
│   │       ├── ImportServiceImpl.java
│   │       ├── DeduplicationServiceImpl.java
│   │       ├── BlacklistServiceImpl.java
│   │       └── ContactNumberValidatorImpl.java
│   │
│   ├── mapper/
│   │   ├── ContactMapper.java
│   │   ├── ContactListMapper.java
│   │   ├── ContactListItemMapper.java
│   │   └── BlacklistMapper.java
│   │
│   ├── entity/
│   │   ├── Contact.java
│   │   ├── ContactList.java
│   │   ├── ContactListItem.java
│   │   └── Blacklist.java
│   │
│   ├── dto/
│   │   ├── ContactCreateRequest.java
│   │   ├── ContactUpdateRequest.java
│   │   ├── ContactListCreateRequest.java
│   │   ├── ImportRequest.java                  # 导入请求
│   │   └── ImportProgressVO.java               # 导入进度
│   │
│   ├── vo/
│   │   ├── ContactVO.java
│   │   ├── ContactListVO.java
│   │   ├── BlacklistVO.java
│   │   └── ImportResultVO.java
│   │
│   ├── api/
│   │   ├── ContactApi.java                     # 供 campaign-service 调用
│   │   └── BlacklistApi.java                   # 黑名单校验接口
│   │
│   ├── enums/
│   │   ├── ContactStatus.java                  # 线索状态
│   │   ├── ContactSource.java                  # 线索来源
│   │   ├── ImportStatus.java                   # 导入状态
│   │   └── BlacklistScope.java                 # 黑名单范围
│   │
│   └── config/
│       └── ContactProperties.java
│
└── src/main/resources/
    └── application.yml
```

---

## 3. 数据模型设计

所有表落在**租户专属 Schema**（`tenant_{tenantCode}`）。

### 3.1 表关系

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────────┐
│ contact            │     │ contact_list      │     │ contact_list_item     │
│──────────────────│     │──────────────────│     │──────────────────────│
│ id                 │     │ id                │     │ id                    │
│ phone              │     │ list_name         │     │ list_id               │
│ name               │     │ list_code         │     │ contact_id            │
│ gender             │     │ tag               │     │ phone                 │
│ age                │     │ contact_count     │     │ status                │
│ address            │     │ status            │     │ campaign_id            │
│ company            │     └──────────────────┘     │ last_call_result       │
│ tags               │                              │ intent_tag             │
│ source             │                              │ variables              │
│ custom_fields      │   ┌──────────────────┐      └──────────────────────┘
│ status             │   │ blacklist         │
│ remark             │   │──────────────────│
│ last_call_result   │   │ id                │
│ last_call_time     │   │ phone             │
│ last_intent_tag    │   │ scope             │
└──────────────────┘   │ reason            │
                       │ no_call_from      │
                       │ no_call_to        │
                       │ status            │
                       └──────────────────┘
```

### 3.2 Contact

```java
@TableName("contact")
public class Contact extends BaseEntity {

    /** 电话号码（唯一，租户内去重） */
    @Column
    private String phone;

    /** 客户姓名 */
    @Column
    private String name;

    /** 性别：MALE/FEMALE/UNKNOWN */
    @Column
    private String gender;

    /** 年龄 */
    @Column
    private Integer age;

    /** 地址 */
    @Column
    private String address;

    /** 公司/单位 */
    @Column
    private String company;

    /** 标签（JSON 数组，如 ["高意向","老客户"]） */
    @Column
    private String tags;

    /** 线索来源（见 ContactSource 枚举） */
    @Column
    private String source;

    /** 自定义字段（JSON，如 {"wechat":"xxx","province":"广东"}） */
    @Column
    private String customFields;

    /** 线索状态（见 ContactStatus 枚举） */
    @Column
    private String status;

    /** 备注 */
    @Column
    private String remark;

    /** 最近一次呼叫结果 */
    @Column
    private String lastCallResult;

    /** 最近一次呼叫时间 */
    @Column
    private LocalDateTime lastCallTime;

    /** 最近意向标签（A/B/C/D） */
    @Column
    private String lastIntentTag;
}
```

### 3.3 ContactList / ContactListItem

```java
@TableName("contact_list")
public class ContactList extends BaseEntity {

    /** 名单名称 */
    @Column
    private String listName;

    /** 名单编码（租户内唯一） */
    @Column
    private String listCode;

    /** 标签 */
    @Column
    private String tag;

    /** 名单内联系人总数 */
    @Column
    private Integer contactCount;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}

@TableName("contact_list_item")
public class ContactListItem extends BaseEntity {

    /** 所属名单 ID */
    @Column
    private String listId;

    /** 联系人 ID（引用 contact） */
    @Column
    private String contactId;

    /** 电话号码（冗余） */
    @Column
    private String phone;

    /** 名单条目状态：ACTIVE=正常, CALLED=已呼叫, EXCLUDED=已排除 */
    @Column
    private String status;

    /** 关联的外呼任务 ID（若被某任务使用） */
    @Column
    private String campaignId;

    /** 最近呼叫结果 */
    @Column
    private String lastCallResult;

    /** 最近意向标签 */
    @Column
    private String intentTag;

    /** 自定义变量（JSON，供话术变量替换） */
    @Column
    private String variables;
}
```

### 3.4 Blacklist

```java
@TableName("blacklist")
public class Blacklist extends BaseEntity {

    /** 被拉黑的电话号码 */
    @Column
    private String phone;

    /** 黑名单范围：GLOBAL=全局（平台级）, TENANT=租户级 */
    @Column
    private String scope;

    /** 拉黑原因 */
    @Column
    private String reason;

    /** 勿扰时段开始（如 12:00，null 表示全天勿扰） */
    @Column
    private LocalTime noCallFrom;

    /** 勿扰时段结束（如 14:00） */
    @Column
    private LocalTime noCallTo;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}
```

---

## 4. 枚举定义

### 4.1 线索状态（ContactStatus）

```java
public enum ContactStatus {
    NEW,           // 新建（待跟进）
    IN_PROGRESS,   // 跟进中
    QUALIFIED,     // 已意向（高价值）
    CONVERTED,     // 已成交
    UNQUALIFIED,   // 无意向（无效线索）
    BLACKLISTED    // 已拉黑
}
```

### 4.2 线索来源（ContactSource）

```java
public enum ContactSource {
    IMPORT,     // 批量导入（Excel/CSV）
    API,        // API 接入
    MANUAL,     // 手动录入
    OUTBOUND,   // 外呼回传（通话后补充）
    SYSTEM      // 系统生成
}
```

### 4.3 黑名单范围（BlacklistScope）

```java
public enum BlacklistScope {
    GLOBAL,     // 全局（平台级，所有租户生效）
    TENANT      // 租户级（仅本租户生效）
}
```

---

## 5. 号码导入

### 5.1 导入流程

```
管理员上传 Excel/CSV 文件
        │
        ▼
┌──────────────────────────────────────┐
│ 1. 校验文件格式                      │  支持 .xlsx / .csv
│    校验表头模板                       │  必填列：phone
│                                     │  可选列：name/gender/tags/custom
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 2. 解析文件（Hutool ExcelReader）     │  逐行读取
│    生成导入任务                       │  异步处理（虚拟线程）
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 3. 逐行校验                          │  号码格式（^1\d{10}$）
│    （虚拟线程批量处理）               │  黑名单过滤（BlacklistApi）
│                                     │  去重（DeduplicationService）
└────────────┬─────────────────────────┘
             ▼
┌──────────────────────────────────────┐
│ 4. 写入数据库                        │  有效 → 新增/更新 Contact + ListItem
│    返回导入结果                       │  无效 → 记录错误行
└────────────┬─────────────────────────┘
             ▼
       返回 ImportResultVO（成功/失败/跳过统计）
```

### 5.2 导入校验规则

| 校验项 | 规则 | 处理方式 |
|--------|------|----------|
| 号码格式 | `^1\d{10}$`（中国大陆手机号） | 无效 → 跳过并记录 |
| 黑名单过滤 | 号码在 GLOBAL/TENANT 黑名单中 | 跳过并记录 |
| 去重 | 号码已存在 | 更新为最新数据（upsert） |
| 必填字段 | phone 非空 | 缺失 → 跳过并记录 |
| 数据量 | 单次导入 ≤ 100,000 条 | 超过拆分多批处理 |

### 5.3 导入进度

```
导入任务提交
        │
        ▼
┌──────────────────────────────────────┐
│ 导入状态：PROCESSING                 │  实时更新进度到 Redis
│  progress:{importId}                  │  processed / total
│                                        │  success / failed / skipped
└────────────┬─────────────────────────┘
             ▼
        完成
        │
        ▼
┌──────────────────────────────────────┐
│ 导入状态：COMPLETED                  │
│ 生成 ImportResultVO                  │  成功数、失败数、错误明细
│ 更新名单 contactCount                │
└──────────────────────────────────────┘
```

---

## 6. 号码去重

### 6.1 去重策略

| 去重维度 | 说明 | 处理方式 |
|----------|------|----------|
| 租户内号码唯一 | 同一租户同一号码只保留一条 Contact | 导入 upsert，新数据覆盖旧数据 |
| 名单间共享 | 同一号码可存在于多个名单 | 允许（List 维度不做唯一约束） |
| 跨任务互斥 | 同一号码同一时间只归属一个任务 | campaign 分配时校验 |

### 6.2 去重实现

```java
public interface DeduplicationService {

    /**
     * 检查号码是否已存在
     */
    boolean exists(String phone);

    /**
     * 批量 upsert：号码存在则更新，不存在则新增
     * 返回处理结果（新增数/更新数）
     */
    BatchUpsertResult batchUpsert(List<Contact> contacts);
}
```

**设计决策**：`contact.phone` 加唯一索引（租户 Schema 内），通过数据库唯一约束保证去重，避免并发导入时的竞态条件。upsert 使用 PostgreSQL 的 `ON CONFLICT DO UPDATE`。

---

## 7. 黑名单管理

### 7.1 黑名单范围

| 范围 | 维护方 | 说明 |
|------|--------|------|
| GLOBAL（全局） | 平台管理员 | 骚扰号码、投诉号码，所有租户外呼时自动过滤 |
| TENANT（租户级） | 租户管理员 | 本租户客户主动要求不再联系 |

### 7.2 勿扰时段

`noCallFrom` / `noCallTo` 定义该号码的勿扰时段（如午休 12:00-14:00）。外呼任务在此时段跳过该号码。

### 7.3 校验接口

```java
/**
 * 黑名单校验接口 — 供 campaign-service 外呼前调用
 */
@HttpExchange(url = "${service.contact.url}", name = "blacklistApi")
public interface BlacklistApi {

    /**
     * 校验号码是否可外呼
     * @param tenantId 租户 ID
     * @param phone 电话号码
     * @return true=可外呼，false=在黑名单/勿扰时段
     */
    @PostExchange("/api/internal/blacklist/check")
    boolean canCall(@RequestParam String tenantId, @RequestParam String phone);

    /**
     * 批量校验号码是否可外呼
     */
    @PostExchange("/api/internal/blacklist/check-batch")
    Map<String, Boolean> canCallBatch(@RequestBody List<String> phones);
}
```

### 7.4 黑名单接口

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 黑名单列表 | GET | `/api/blacklist/list` | `blacklist:list` | 分页 |
| 添加黑名单 | POST | `/api/blacklist` | `blacklist:create` | 支持批量 |
| 移除黑名单 | DELETE | `/api/blacklist/{id}` | `blacklist:delete` | |
| 批量导入黑名单 | POST | `/api/blacklist/import` | `blacklist:import` | Excel/CSV |

---

## 8. 对外接口设计

### 8.1 供 campaign 调用的接口

```java
/**
 * 联系人接口 — 供 campaign-service 创建任务时调用
 */
@HttpExchange(url = "${service.contact.url}", name = "contactApi")
public interface ContactApi {

    /**
     * 获取名单详情（含号码总数）
     */
    @GetExchange("/api/internal/list/{listId}")
    ContactListVO getList(@PathVariable String listId);

    /**
     * 分页获取名单内的号码明细（campaign 导入号码时使用）
     */
    @GetExchange("/api/internal/list/{listId}/items")
    Page<ContactListItemVO> getListItems(@PathVariable String listId, 
        Page<?> page);

    /**
     * 回写号码状态（campaign 消费通话结果后调用）
     */
    @PostExchange("/api/internal/item/status")
    void updateItemStatus(@RequestBody ItemStatusUpdateRequest request);
}
```

### 8.2 管理后台 API

| 接口 | 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|------|
| 线索列表 | GET | `/api/contact/list` | `contact:list` | 分页，支持标签/状态筛选 |
| 线索详情 | GET | `/api/contact/{id}` | `contact:list` | |
| 创建线索 | POST | `/api/contact` | `contact:create` | 手动录入 |
| 更新线索 | PUT | `/api/contact/{id}` | `contact:update` | |
| 删除线索 | DELETE | `/api/contact/{id}` | `contact:delete` | |
| 名单列表 | GET | `/api/list/list` | `contact:list` | |
| 创建名单 | POST | `/api/list` | `contact:create` | |
| 导入号码 | POST | `/api/import` | `contact:import` | Excel/CSV 导入 |
| 导入进度 | GET | `/api/import/{importId}/progress` | `contact:import` | 实时进度 |
| 导出号码 | GET | `/api/list/{id}/export` | `contact:list` | 导出名单号码 |
| 号码去重检查 | POST | `/api/contact/deduplicate` | `contact:list` | |

---

## 9. 错误码定义（contact 区间 8000-8999）

新增 `ContactErrorCode` 枚举，通过 `new BizException(ContactErrorCode.XXX)` 抛出。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 8001 | CONTACT_NOT_FOUND | 线索不存在 | |
| 8002 | CONTACT_PHONE_INVALID | 号码格式无效 | 导入/创建时号码格式错误 |
| 8003 | CONTACT_BLACKLISTED | 号码在黑名单中 | 创建/导入时号码被拉黑 |
| 8004 | LIST_NOT_FOUND | 名单不存在 | |
| 8005 | LIST_CODE_DUPLICATE | 名单编码已存在 | |
| 8006 | LIST_ITEM_NOT_FOUND | 名单条目不存在 | |
| 8007 | IMPORT_FILE_INVALID | 导入文件格式无效 | 文件损坏/表头错误 |
| 8008 | IMPORT_TOO_LARGE | 导入数据量超限 | 单次导入 > 100,000 条 |
| 8009 | IMPORT_IN_PROGRESS | 已有导入任务进行中 | 重复提交导入 |
| 8010 | BLACKLIST_NOT_FOUND | 黑名单记录不存在 | |
| 8011 | BLACKLIST_EXISTS | 号码已在黑名单中 | 重复添加 |
| 8012 | PHONE_IN_DO_NOT_CALL | 号码处于勿扰时段 | 外呼前校验失败 |

---

## 10. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `import:progress:{importId}` | Hash | 24h | 导入进度（processed/success/failed/skipped） |
| `contact:count:{listId}` | String(int) | 1h | 名单号码数量缓存 |
| `blacklist:check:{tenantId}` | Set(phone) | 1h | 黑名单号码集合缓存（外呼前校验） |

---

## 11. 依赖与配置

### 11.1 Maven 依赖

```xml
<dependencies>
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-common</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-processor</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**依赖说明**：
- 使用 Hutool `ExcelReader` 解析 Excel，无需自研导入解析器
- 不引入 Kafka（contact-service 不消费事件，号码状态由 campaign 通过 @HttpExchange 回写）
- 不引入 Dubbo（纯 @HttpExchange 配置/数据查询服务）

### 11.2 application.yml 核心配置

```yaml
server:
  port: 8087

spring:
  application:
    name: vhuan-contact
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

contact:
  # 单次导入最大条数
  import-max-size: 100000
  # 导入并发处理线程数
  import-concurrency: 8
  # 号码格式正则
  phone-pattern: "^1\\d{10}$"
```

---

## 12. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 号码去重 | 应用层检查 vs 数据库唯一约束 | **数据库唯一约束 + upsert** | 并发导入时应用层检查存在竞态，DB `ON CONFLICT` 保证原子性 |
| 名单与线索关系 | 独立表 vs 冗余 | **独立表 + 冗余号码** | ListItem 冗余 phone 便于任务分配查询，避免跨表 JOIN |
| 号码状态回写 | 直接写库 vs campaign 转写 | **campaign 转写（@HttpExchange）** | contact-service 不消费 Kafka，保持职责单一；campaign 消费事件后回写 |
| 导入解析 | 自研 vs Hutool ExcelReader | **Hutool ExcelReader** | 复用成熟工具，避免重复造轮子 |
| 黑名单存储 | 共享 Schema vs 租户 Schema | **租户 Schema** | GLOBAL 黑名单通过平台任务同步到各租户，TENANT 黑名单租户独立 |
| 是否引入 Kafka | 引入 vs 不引入 | **不引入** | contact-service 是被动数据源，号码状态由 campaign 回写，无需事件驱动 |

---

## 13. 自检清单

- [ ] 名单管理：创建/查询/导入/导出/标签管理
- [ ] 线索管理：CRUD、状态流转（NEW→IN_PROGRESS→QUALIFIED→CONVERTED/UNQUALIFIED）
- [ ] 号码去重：phone 唯一约束 + upsert，导入时防并发竞态
- [ ] 导入：Excel/CSV/API 三种方式，单次 ≤ 100,000 条，异步处理（虚拟线程）
- [ ] 导入校验：号码格式、黑名单过滤、必填字段、数据量限制
- [ ] 导入进度：Redis 实时更新（processed/success/failed/skipped）
- [ ] 黑名单：GLOBAL + TENANT 两种范围，勿扰时段（noCallFrom/noCallTo）
- [ ] 黑名单校验接口：BlacklistApi.canCall/canCallBatch 供 campaign 调用
- [ ] 对外暴露 ContactApi：getList/getListItems/updateItemStatus 供 campaign 调用
- [ ] 错误码使用 `ContactErrorCode`（8000-8999 区间）
- [ ] 数据表落在租户 Schema，4 张表（contact/list/list_item/blacklist）
- [ ] 号码回写通过 @HttpExchange，contact-service 不消费 Kafka
- [ ] 不引入 Dubbo，纯 @HttpExchange 服务
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，继续 `vhuan-analytics` 详细设计。
