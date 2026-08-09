# vhuan-auth 详细设计

> **模块**: vhuan-auth（认证授权服务）  
> **阶段**: 第二阶段 — 核心业务链路  
> **版本**: v1.0.0  
> **日期**: 2026-08-09  
> **状态**: 设计中

---

## 1. 设计目标

提供统一的认证与授权能力：用户登录认证、JWT 签发与校验、RBAC 权限管理、租户 API Key 管理。是核心业务链路（`auth → tenant → agent → campaign → call → ai-engine`）的入口。

**职责边界**：
- 用户认证：账号密码登录、手机验证码登录、登出、Token 刷新
- JWT 管理：Access Token（30min）+ Refresh Token（7d）签发、校验、续期
- RBAC 权限：平台管理员 / 租户管理员 / 主管 / 坐席 四级角色，权限树管理
- 租户 API Key：生成、校验、吊销（供第三方系统以 API 方式接入）
- 密码安全：BCrypt 加密存储，首次登录强制改密

**非职责**：
- 不校验每个请求的 Token（由 `vhuan-gateway` 的 `AuthGlobalFilter` 统一校验）
- 不处理租户业务数据（由 `vhuan-tenant` 负责）
- 不生成通话、话术等业务对象

**与 Gateway 的分工**：
- **Gateway 只做无状态 JWT 校验**（验签 + 过期 + 解析 claims），不放行则拦截
- **auth 服务负责任有状态的操作**（登录签发、刷新、登出拉黑、RBAC 查询、API Key 管理）

---

## 2. 模块结构

```
vhuan-auth/
├── pom.xml
├── src/main/java/com/vhuan/auth/
│   ├── AuthApplication.java            # 启动类
│   ├── controller/
│   │   ├── AuthController.java         # 登录/登出/刷新/验证码
│   │   ├── UserController.java         # 用户 CRUD、改密、分配角色
│   │   ├── RoleController.java         # 角色 CRUD、权限分配
│   │   └── ApiKeyController.java       # API Key 管理
│   ├── service/
│   │   ├── AuthService.java            # 认证核心逻辑
│   │   ├── UserService.java            # 用户管理
│   │   ├── RoleService.java            # 角色权限
│   │   ├── ApiKeyService.java          # API Key 管理
│   │   └── impl/
│   │       ├── AuthServiceImpl.java
│   │       ├── UserServiceImpl.java
│   │       ├── RoleServiceImpl.java
│   │       └── ApiKeyServiceImpl.java
│   ├── mapper/
│   │   ├── SysUserMapper.java          # MyBatis-Flex Mapper
│   │   ├── SysRoleMapper.java
│   │   ├── SysPermissionMapper.java
│   │   ├── SysUserRoleMapper.java
│   │   ├── SysRolePermissionMapper.java
│   │   └── TenantApiKeyMapper.java
│   ├── entity/
│   │   ├── SysUser.java
│   │   ├── SysRole.java
│   │   ├── SysPermission.java
│   │   ├── SysUserRole.java
│   │   ├── SysRolePermission.java
│   │   └── TenantApiKey.java
│   ├── dto/
│   │   ├── LoginRequest.java           # 登录请求
│   │   ├── LoginResponse.java          # 登录响应（含 Token）
│   │   ├── RefreshTokenRequest.java
│   │   ├── UserCreateRequest.java
│   │   ├── UserUpdateRequest.java
│   │   └── ApiKeyResponse.java
│   ├── vo/
│   │   ├── UserVO.java
│   │   ├── RoleVO.java
│   │   └── PermissionVO.java
│   ├── context/
│   │   └── UserContextHolder.java      # 当前用户上下文（从 Gateway 注入的请求头读取）
│   └── config/
│       ├── JwtProperties.java          # JWT 配置属性绑定
│       ├── JwtUtil.java                # JWT 生成/解析（基于 jjwt）
│       └── PasswordConfig.java         # BCryptPasswordEncoder Bean
│
└── src/main/resources/
    └── application.yml
```

---

## 3. 数据模型设计

### 3.1 表结构与关系

```
┌──────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  sys_user     │    │  sys_user_role    │    │  sys_role         │
│──────────────│    │──────────────────│    │──────────────────│
│ id            │───▶│ id                │    │ id                │
│ username      │    │ user_id           │    │ role_code         │
│ password      │    │ role_id           │    │ role_name         │
│ phone         │    └──────────────────┘    │ tenant_id         │
│ real_name     │                             │ data_scope        │
│ avatar        │                             │ status            │
│ status        │                             └────────┬─────────┘
│ tenant_id     │                                      │
│ is_super_admin│         ┌────────────────────────────┘
│ pwd_updated_at│         │
│ last_login_at │    ┌────▼──────────┐   ┌──────────────────────┐
│ last_login_ip │    │ sys_role_perm │   │ sys_permission        │
└──────────────┘    │───────────────│   │──────────────────────│
                    │ id             │   │ id                    │
┌──────────────┐    │ role_id        │──▶│ perm_code             │
│ tenant_api_key│   │ permission_id  │   │ perm_name             │
│──────────────│    └────────────────┘   │ perm_type             │
│ id            │                        │ parent_id             │
│ tenant_id     │                        │ status                │
│ api_key       │                        └──────────────────────┘
│ api_secret    │
│ scope         │
│ status        │
│ expire_time   │
│ remark        │
│ last_used_at  │
└──────────────┘
```

### 3.2 SysUser

```java
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 登录账号 */
    @Column
    private String username;

    /** 登录密码（BCrypt 加密） */
    @Column
    private String password;

    /** 手机号（用于验证码登录） */
    @Column
    private String phone;

    /** 真实姓名 */
    @Column
    private String realName;

    /** 头像 URL */
    @Column
    private String avatar;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;

    /** 所属租户 ID（system 表示平台用户） */
    @Column
    private String tenantId;

    /** 是否超级管理员（平台级，拥有全部权限） */
    @Column
    private Boolean isSuperAdmin;

    /** 密码最近修改时间（判断是否需要强制改密） */
    @Column
    private LocalDateTime pwdUpdatedAt;

    /** 最近登录时间 */
    @Column
    private LocalDateTime lastLoginAt;

    /** 最近登录 IP */
    @Column
    private String lastLoginIp;
}
```

**表设计要点**：
- 继承 `BaseEntity`，主键雪花 ID（String），含审计字段与逻辑删除
- 密码字段仅用于账号密码登录，BCrypt 加密
- `tenantId = "system"` 表示平台管理员用户，拥有跨租户管理权限
- `isSuperAdmin` 为平台级超级管理员快捷标记，跳过 RBAC 校验

### 3.3 SysRole / SysPermission

```java
@TableName("sys_role")
public class SysRole extends BaseEntity {
    /** 角色编码（唯一，如 TENANT_ADMIN / SUPERVISOR / AGENT） */
    @Column
    private String roleCode;

    /** 角色名称 */
    @Column
    private String roleName;

    /** 所属租户 ID（system 表示平台内置角色） */
    @Column
    private String tenantId;

    /** 数据权限范围：ALL=全部，TENANT=本租户，SELF=本人 */
    @Column
    private String dataScope;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}

@TableName("sys_permission")
public class SysPermission extends BaseEntity {
    /** 权限编码（唯一，如 call:view / call:intercept） */
    @Column
    private String permCode;

    /** 权限名称 */
    @Column
    private String permName;

    /** 权限类型：MENU=菜单，BUTTON=按钮，API=接口 */
    @Column
    private String permType;

    /** 父权限 ID（构建权限树） */
    @Column
    private String parentId;

    /** 状态：1=启用，0=禁用 */
    @Column
    private Integer status;
}
```

### 3.4 预置角色与权限

| 角色编码 | 角色名称 | 数据范围 | 典型权限 |
|----------|----------|----------|----------|
| `PLATFORM_ADMIN` | 平台管理员 | ALL | 全部（含租户管理） |
| `TENANT_ADMIN` | 租户管理员 | TENANT | 租户内全部，含用户/角色/套餐 |
| `SUPERVISOR` | 主管 | TENANT | 任务查看、坐席绩效、通话监听 |
| `AGENT` | 坐席 | SELF | 通话处理、手动外呼、个人报表 |

权限编码示例：
- `user:list` / `user:create` / `user:update` / `user:delete`
- `role:list` / `role:assign`
- `apikey:list` / `apikey:create` / `apikey:revoke`
- `campaign:list` / `campaign:start` / `campaign:stop`
- `call:view` / `call:intercept` / `call:transfer`

---

## 4. 认证流程设计

### 4.1 账号密码登录

```
用户提交 (username, password)
        │
        ▼
┌─────────────────────────┐
│ 1. 校验参数              │  空值/格式校验，失败抛 PARAM_INVALID
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 2. 查询用户              │  username + tenantId 查询 SysUser
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 3. 校验状态              │  用户不存在→ACCOUNT_NOT_FOUND
│                         │  已禁用→ACCOUNT_DISABLED
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 4. BCrypt 校验密码       │  失败→PASSWORD_ERROR（记录失败次数）
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 5. 查询角色权限          │  加载用户角色、权限编码集合
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 6. 签发 Token           │  AccessToken(30min) + RefreshToken(7d)
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 7. 更新登录信息          │  lastLoginAt / lastLoginIp
└────────────┬────────────┘
             ▼
       返回 LoginResponse
```

### 4.2 Token 体系

#### JWT 结构（Access Token）

```json
{
  "sub": "1001",                    // 用户 ID
  "tenantId": "t001",               // 租户 ID
  "username": "zhangsan",
  "roles": ["TENANT_ADMIN"],        // 角色编码
  "perms": ["user:list", "call:view"],  // 权限编码（冗余，减少网关查询）
  "iat": 1723100000,                // 签发时间
  "exp": 1723101800                 // 过期时间（30min）
}
```

**设计决策**：Access Token 内**冗余携带 roles 和 perms**，使 Gateway 的 `AuthGlobalFilter` 无需访问数据库即可完成鉴权，降低网关延迟。代价是 Token 体积略大、权限变更需等 Token 过期生效，但 30min 的短有效期可接受。

#### Token 类型

| 类型 | 有效期 | 存储 | 用途 |
|------|--------|------|------|
| Access Token | 30min | 无状态（JWT 自包含） | 每请求携带，Gateway 校验 |
| Refresh Token | 7d | Redis（允许拉黑） | 刷新 Access Token，登出时拉黑 |

**Refresh Token 为什么存 Redis**：实现登出即时失效。Access Token 无状态无法主动失效（只能等过期），Refresh Token 存 Redis 后，登出时删除 Redis 键，使刷新请求立即失效。

### 4.3 Token 刷新流程

```
客户端携带 RefreshToken 请求 /api/auth/refresh
        │
        ▼
┌─────────────────────────┐
│ 1. 校验 RefreshToken 格式 │  无效→REFRESH_TOKEN_INVALID
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 2. Redis 校验            │  键 refresh_token:{jti} 是否存在
│                         │  不存在→REFRESH_TOKEN_EXPIRED（已登出/已过期）
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 3. 校验用户状态          │  用户禁用→ACCOUNT_DISABLED
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 4. 重新查询角色权限      │  权限可能已变更，重新加载
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 5. 签发新 Token          │  新 Access + 新 Refresh（旧 Refresh 删除）
└────────────┬────────────┘
             ▼
       返回 LoginResponse
```

**设计决策**：刷新时**删除旧 Refresh Token 并签发新 Refresh Token**（旋转机制），降低 Refresh Token 泄露风险。若检测到旧 Refresh Token 被重复使用，视为泄露，吊销该用户全部会话（TODO：记录安全告警）。

### 4.4 登出流程

```
客户端携带 AccessToken 请求 /api/auth/logout
        │
        ▼
┌─────────────────────────┐
│ 1. 解析 Access Token     │  提取用户 ID + Token jti
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 2. 删除 Redis Refresh    │  删除 refresh_token:{jti}
│    Token 键             │
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 3. 记录登出日志          │  audit_log
└────────────┬────────────┘
             ▼
          返回成功
```

> **安全说明**：Access Token 在剩余有效期内仍可用（无状态 JWT 无法主动失效）。如需严格即时失效，可在 Redis 维护黑名单（`blacklist:{jti}`），Gateway 校验时查询——但会增加网关延迟，默认不启用，仅在强制下线场景（账号被禁用、密码被修改）时使用。TODO：评估黑名单对网关性能的影响。

---

## 5. 认证与 Token 接口

### 5.1 接口清单

| 接口 | 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|------|
| 账号密码登录 | POST | `/api/auth/login` | 否 | 返回 Access + Refresh Token |
| 手机验证码登录 | POST | `/api/auth/login/sms` | 否 | 需先获取验证码 |
| 发送验证码 | POST | `/api/auth/captcha` | 否 | 发送短信验证码（对接 notification） |
| Token 刷新 | POST | `/api/auth/refresh` | 否 | 用 Refresh Token 换新 Token |
| 登出 | POST | `/api/auth/logout` | 是 | 注销当前会话 |
| 获取当前用户信息 | GET | `/api/auth/me` | 是 | 返回用户 + 角色 + 权限 |

### 5.2 登录响应

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": {
    "id": "1001",
    "username": "zhangsan",
    "realName": "张三",
    "avatar": "https://...",
    "tenantId": "t001",
    "roles": ["TENANT_ADMIN"],
    "perms": ["user:list", "call:view"]
  }
}
```

---

## 6. 用户 / 角色 / 权限管理

### 6.1 用户管理接口

| 接口 | 方法 | 路径 | 权限 |
|------|------|------|------|
| 用户列表 | GET | `/api/user/list` | `user:list` |
| 用户详情 | GET | `/api/user/{id}` | `user:list` |
| 创建用户 | POST | `/api/user` | `user:create` |
| 更新用户 | PUT | `/api/user/{id}` | `user:update` |
| 删除用户 | DELETE | `/api/user/{id}` | `user:delete` |
| 分配角色 | PUT | `/api/user/{id}/roles` | `role:assign` |
| 修改密码 | PUT | `/api/user/{id}/password` | 本人或管理员 |
| 重置密码 | PUT | `/api/user/{id}/password/reset` | `user:update` |

**密码策略**：
- 新用户创建时使用 `SystemConstants.DEFAULT_PASSWORD`（`Vhuan@2024`），`pwdUpdatedAt` 为空触发首次强制改密
- 密码强度：≥8 位，含字母 + 数字（校验规则）
- BCrypt 加密存储（`BCryptPasswordEncoder`，成本因子 10）
- 管理员重置密码后，同样触发该用户下次登录强制改密

**强制改密流程**：登录后若 `pwdUpdatedAt == null`（首次）或超过 `90` 天未修改，返回的 `LoginResponse` 附带 `mustChangePassword: true`，前端引导跳转改密页。

### 6.2 角色管理接口

| 接口 | 方法 | 路径 | 权限 |
|------|------|------|------|
| 角色列表 | GET | `/api/role/list` | `role:list` |
| 创建角色 | POST | `/api/role` | `role:create` |
| 更新角色 | PUT | `/api/role/{id}` | `role:update` |
| 删除角色 | DELETE | `/api/role/{id}` | `role:delete` |
| 分配权限 | PUT | `/api/role/{id}/permissions` | `role:assign` |
| 权限树 | GET | `/api/role/permission-tree` | `role:list` |

**租户隔离**：`tenantId` 维度隔离角色，租户管理员只能操作本租户的角色，无法看到其他租户角色。平台内置角色（`tenantId=system`）对所有租户只读可见。

### 6.3 RBAC 权限校验

```
请求进入 auth 服务业务接口（经由 Gateway 校验 Token 后转发）
        │
        ▼
┌─────────────────────────┐
│ 1. UserContextHolder 读取 │  从 X-User-Id、X-User-Roles 请求头读取
│    当前用户信息          │  （Gateway 已注入）
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 2. 超级管理员判定        │  isSuperAdmin=true → 放行
└────────────┬────────────┘
             ▼
┌─────────────────────────┐
│ 3. 权限校验              │  用户权限集合是否包含所需 permCode
└────────────┬────────────┘
             │ 无权限
             ▼
      抛 ForbiddenException（403）
```

**设计决策**：权限校验使用**注解 + AOP**实现（`@RequirePermission("user:list")`），避免在每个方法手写判断。权限集合在登录时加载到 Token，业务侧从 `UserContextHolder` 读取即可，无需每次查库。

---

## 7. 租户 API Key 管理

为第三方系统提供 API 接入能力（如外部 CRM 推送名单、查询任务状态）。

### 7.1 API Key 机制

```
生成：
  api_key = vhuan_{tenantId}_{32位随机串}      # 身份标识，明文存储
  api_secret = 48位随机串                       # 密钥，仅返回一次，DB 存哈希

调用（HTTP Header）：
  X-API-Key: vhuan_t001_xxx
  X-API-Timestamp: 1723100000                 # Unix 秒，防重放
  X-API-Signature: HMAC-SHA256(api_secret, method + path + body + timestamp)
```

**签名算法**：
```
signature = HmacSHA256(api_secret, "{method}\n{path}\n{body}\n{timestamp}")
```

**校验流程**：
1. 按 `X-API-Key` 查 `tenant_api_key` 表，校验状态与有效期
2. 校验 `X-API-Timestamp` 与当前时间差 ≤ 5min（防重放）
3. 用 DB 存储的 secret 哈希重建签名对比（防篡改）
4. 更新 `last_used_at`

### 7.2 API Key 接口

| 接口 | 方法 | 路径 | 权限 |
|------|------|------|------|
| 创建 API Key | POST | `/api/apikey` | `apikey:create` |
| 列表 | GET | `/api/apikey/list` | `apikey:list` |
| 吊销 | PUT | `/api/apikey/{id}/revoke` | `apikey:revoke` |
| 重新生成 Secret | PUT | `/api/apikey/{id}/regen-secret` | `apikey:update` |

**设计决策**：`api_secret` 仅创建时明文返回一次，DB 存储其 SHA-256 哈希。泄露后可吊销或重新生成 Secret，无需重建 Key。

---

## 8. 错误码定义（auth 区间 2000-2999）

在 `vhuan-common` 的 `BizErrorCode` 约定下，auth 模块扩展自身错误码。**设计决策**：auth 模块定义独立的 `AuthErrorCode` 枚举（或直接扩展 `BizErrorCode`），错误码落在 2000-2999 区间。

| 错误码 | 名称 | 消息 | 场景 |
|--------|------|------|------|
| 2001 | TOKEN_MISSING | 缺少认证 Token | 未携带 Token |
| 2002 | TOKEN_INVALID | 无效的 Token | Token 解析失败 |
| 2003 | TOKEN_EXPIRED | Token 已过期 | Access/Refresh 过期 |
| 2004 | ACCOUNT_NOT_FOUND | 账号不存在 | 登录用户名不存在 |
| 2005 | ACCOUNT_DISABLED | 账号已禁用 | 用户 status=0 |
| 2006 | PASSWORD_ERROR | 密码错误 | BCrypt 校验失败 |
| 2007 | PASSWORD_LOCKED | 密码错误次数过多，账号已锁定 | 连续 5 次错误锁定 15min |
| 2008 | REFRESH_TOKEN_INVALID | 无效的刷新令牌 | 刷新 Token 校验失败 |
| 2009 | REFRESH_TOKEN_EXPIRED | 刷新令牌已过期或已注销 | Redis 中不存在 |
| 2010 | NO_PERMISSION | 无访问权限 | RBAC 校验失败（403） |
| 2011 | CAPTCHA_ERROR | 验证码错误 | 手机验证码校验失败 |
| 2012 | API_KEY_INVALID | 无效的 API Key | API Key 不存在/禁用/过期 |
| 2013 | API_SIGNATURE_ERROR | 签名校验失败 | HMAC 签名不匹配 |
| 2014 | PASSWORD_WEAK | 密码强度不足 | 新密码不满足策略 |

**落地方式**：因 `BizErrorCode` 为普通枚举（Graceful Response 3.4.0 无枚举接口），auth 模块新增 `AuthErrorCode` 枚举，用法与 `BizErrorCode` 一致，通过 `new BizException(AuthErrorCode.XXX, detail)` 抛出。

---

## 9. 密码登录安全策略

### 9.1 登录失败锁定

基于 Redis 实现，防暴力破解：

```
key: login_fail:{username}:{tenantId}
值：连续失败次数（自增，过期 15min）

逻辑：
  失败 → INCR + EXPIRE 15min
  达到 5 次 → 锁定（抛 PASSWORD_LOCKED，拒绝后续尝试）
  成功 → 删除 key
```

### 9.2 验证码

手机验证码登录流程：

```
1. POST /api/auth/captcha {phone, type}     → 生成 6 位验证码，存 Redis 5min
2. POST /api/auth/login/sms {phone, code}    → 校验验证码
3. 校验通过 → 查询/创建用户 → 签发 Token
```

**验证码存储**：
```
key: sms_code:{phone}:login
值：6 位数字，有效期 5min，单手机号 60s 内只能发一次（频率限制）
```

**设计决策**：验证码发送对接 `vhuan-notification` 服务的短信渠道，auth 服务只负责生成与校验，不直接对接短信服务商。

---

## 10. JWT 配置与工具

### 10.1 JwtProperties

```java
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtProperties {
    /** 签名密钥（环境变量注入，不硬编码） */
    private String secret;
    /** Access Token 有效期（分钟），默认 30 */
    private long expiration = 30;
    /** Refresh Token 有效期（天），默认 7 */
    private long refreshExpiration = 7;
    /** Token 签发者 */
    private String issuer = "vhuan-auth";
}
```

### 10.2 JwtUtil

```java
@Component
public class JwtUtil {

    /** 生成 Access Token（携带 roles、perms claims） */
    String createAccessToken(UserPrincipal user);

    /** 生成 Refresh Token（携带 jti，用于 Redis 存储键） */
    String createRefreshToken(String userId, String jti);

    /** 解析并校验 Access Token，返回 Claims */
    Claims parseAccessToken(String token);

    /** 解析 Refresh Token（仅校验签名，过期判断由 Redis 处理） */
    Claims parseRefreshToken(String token);
}
```

**设计决策**：基于已集成的 `jjwt`（0.12.6）实现。使用 **HS256** 对称签名（单服务集群场景足够；如未来拆分独立认证中心可换 RS256）。

---

## 11. Redis 键设计

| 键 | 类型 | 有效期 | 说明 |
|----|------|--------|------|
| `refresh_token:{jti}` | String(userId) | 7d | Refresh Token 会话，登出时删除 |
| `login_fail:{username}:{tenantId}` | String(次数) | 15min | 登录失败计数 |
| `sms_code:{phone}:login` | String(验证码) | 5min | 手机验证码 |
| `user_session:{userId}` | Set(jti) | 7d | 用户活跃会话集合（可选，用于强制下线） |

**设计决策**：使用已集成的 Redisson 作为 Redis 客户端，支持分布式环境下的原子 INCR、EXPIRE 等操作。

---

## 12. 依赖与配置

### 12.1 Maven 依赖

```xml
<dependencies>
    <!-- vhuan-common：异常体系、实体基类、常量 -->
    <dependency>
        <groupId>com.vhuan</groupId>
        <artifactId>vhuan-common</artifactId>
    </dependency>

    <!-- Spring Web（Controller） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- 参数校验 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- MyBatis-Flex（ORM + 分页） -->
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mybatis-flex</groupId>
        <artifactId>mybatis-flex-processor</artifactId>
        <scope>provided</scope>
    </dependency>

    <!-- PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Redis（Redisson） -->
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>

    <!-- Spring Security Crypto（仅 BCryptPasswordEncoder，不启用完整 Security） -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-crypto</artifactId>
    </dependency>

    <!-- jjwt：JWT 签发与解析 -->
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

    <!-- Nacos 服务注册（auth 注册到网关路由） -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>

    <!-- Hutool（工具类） -->
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>

    <!-- MapStruct（DTO/VO 转换） -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**依赖说明**：
- 仅引入 `spring-security-crypto` 获取 `BCryptPasswordEncoder`，**不启用完整的 Spring Security**（网关已统一鉴权，auth 服务内用 `@RequirePermission` 注解 AOP 控制）
- `vhuan-common` 已传递 `spring-boot-starter-web`，此处无需重复声明（但保留以便清晰）
- 使用 Redisson 而非 Jedis，与项目既有 `redisson` 依赖一致

### 12.2 application.yml 核心配置

```yaml
server:
  port: 8081

spring:
  application:
    name: vhuan-auth
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:vhuan}?currentSchema=public
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

jwt:
  secret: ${JWT_SECRET}
  expiration: 30
  refresh-expiration: 7
  issuer: vhuan-auth

# 登录安全策略
security:
  login:
    max-fail-count: 5            # 最大连续失败次数
    lock-minutes: 15             # 锁定时间
    captcha-expire-minutes: 5    # 验证码有效期
    sms-send-interval-seconds: 60 # 短信发送间隔
```

---

## 13. 设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| Access Token 是否冗余角色权限 | 冗余 vs 每次查库 | **冗余到 JWT** | Gateway 无需查库即可鉴权，降低链路延迟；30min 短有效期可接受权限变更滞后 |
| Refresh Token 存储 | JWT 纯无状态 vs Redis | **Redis 存储** | 支持登出即时失效与 Token 旋转 |
| Token 刷新机制 | 复用旧 RT vs 旋转 RT | **旋转（签发新 RT）** | 降低 RT 泄露风险，检测到旧 RT 重用视为泄露 |
| 密码加密 | MD5 vs BCrypt | **BCrypt** | 抗彩虹表，成本因子可调，业界标准 |
| RBAC 校验实现 | 手写判断 vs 注解 AOP | **`@RequirePermission` 注解** | 避免重复代码，声明式表达权限需求 |
| 是否启用完整 Spring Security | 完整启用 vs 仅 crypto | **仅 crypto** | 网关已统一鉴权，完整 Security 配置复杂且与多租户场景冲突 |
| 手机验证码发送 | auth 直连短信商 vs 对接 notification | **对接 notification** | 解耦，短信渠道统一由 notification 管理 |
| 签名算法 | RS256 vs HS256 | **HS256** | 单服务集群对称签名足够，实现简单；未来独立认证中心再升级 RS256 |

---

## 14. 自检清单

- [ ] 认证流程覆盖：账号密码登录、手机验证码登录、刷新、登出、强制改密
- [ ] Token 体系：Access（30min 无状态）+ Refresh（7d Redis），刷新时旋转
- [ ] RBAC：四级角色（PLATFORM_ADMIN / TENANT_ADMIN / SUPERVISOR / AGENT）预置正确
- [ ] 权限校验使用 `@RequirePermission` 注解 AOP，超级管理员放行
- [ ] 密码 BCrypt 加密，首次登录与 90 天超期强制改密
- [ ] 登录失败 5 次锁定 15min（Redis 计数）
- [ ] 错误码使用 `AuthErrorCode`（2000-2999 区间），通过 `BizException` 抛出
- [ ] API Key 签名校验：时间戳防重放 + HMAC 签名防篡改，secret 哈希存储
- [ ] 手机验证码对接 `vhuan-notification`，auth 不直接对接短信商
- [ ] Redisson 管理 Redis 键（refresh_token / login_fail / sms_code）
- [ ] 不启用完整 Spring Security，仅引入 crypto
- [ ] 数据表落在 `public` Schema（租户元数据共享表）
- [ ] 自检：所有新增接口已实现、业务规则覆盖、无无关改动、编译通过

---

> **下一步**：本设计确认后，进入 `vhuan-tenant` 详细设计。