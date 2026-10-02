<div align="center">

# HowSAuth

简称`HSAuth`  
一个 Minecraft 登录插件，使用 PaperAPI，支持 Folia.

</div>

> [!important]
> 大部分代码来自生成式 AI.

## 版本支持
- 使用的 PaperAPI 版本：1.21.  
- 经过测试的服务端：1.21.11，26.1.x，包括 Paper 与 Folia.

## 下载
- [GitHub Releases](https://github.com/HowSberban/HowToLogin/releases)

## 依赖

| 插件                                                                      | 必需 | 说明                           |
|---------------------------------------------------------------------------|------|--------------------------------|
| [PacketEvents](https://github.com/retrooper/packetevents)                 | 是   | 正版验证与背包保护的数据包拦截 |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 否   | 提供登录状态变量               |


## 功能简介

### 常规功能：
- **注册** / **登录** / **登出** / **改密** / **删除账号**
- **自助注销**：玩家凭密码与 2FA 验证码（正版账户免验）验证身份后自行删除账号，二次确认防误操作（`settings.allow-self-unregister` 控制）
- **密码加密存储**：`BCrypt`（默认）或 `SHA-256`，登录时会自动迁移到配置的算法
- **双因素认证（2FA）**：TOTP 动态验证码，扫码或手动输入密钥绑定，支持登录窗口与游戏内弹窗两种验证方式
- **无密码账户**：移除密码后凭 2FA 验证码或正版验证登录，需时可再设回密码
- **无凭据账号拦截**：账号未设密码、未绑验证器且非正版时，默认在连接阶段直接拒绝进入（`protection.reject-no-auth-account`，默认开启）；关闭后玩家可进服但无法完成登录
- **暴力破解防护**：连续失败超过阈值后踢出，失败计数跨连接保留
- **同 IP 限时免密登录**：上次登录 IP 一致且未过期时自动登录
- **登录前限制**：禁止移动、转视角、聊天、执行非白名单命令、与世界交互、物品交互

### 高级功能：
- **登录窗口**：1.21.11+ 服务端与客户端进入世界前弹出图形化窗口（Dialog）完成登录/注册/2FA
- **坐标保护**：玩家加入前修改出生点，支持随机坐标和固定坐标两种模式，登录后传送回退出位置
> [!warning]
> 坐标保护功能在极端情况下会导致玩家上次退出的位置丢失，详见 [坐标保护]() (wiki 还没写)。  
<!-- TODO: 添加 Wiki -->
> 开启登录窗口后无需坐标保护，窗口阶段客户端收不到世界信息。
- **未登录旁观模式**：未登录期间切换为旁观模式，登录后恢复上次游戏模式（新玩家使用服务器默认游戏模式）；退出位置悬空且坐标保护未开启时强制旁观，防止反作弊误判
- **背包保护**：使用 `PacketEvents` 拦截数据包，防止未登录玩家查看自己的背包内容
- **末影珍珠恢复**：退出时捕捉飞行中的珍珠，登录后返还（物品入包或原位重生），防止玩家被吞珍珠
> [!important]
> 珍珠恢复可能会出现非预期行为。  
> Folia 上的珍珠处理很迷，插件无法保证珍珠会被正确捕捉或防止玩家未登录时被珍珠传送。  
> 推荐使用 Folia 的第三方 fork，例如 [Canvas](https://canvasmc.io/downloads/canvas).
- **正版验证**：在离线服务端上通过 `PacketEvents` 验证正版玩家，同时使用正版 UUID
- **正版回退**：在正版验证失败时，允许已注册正版玩家使用密码登录
> [!caution]
> 正版验证处于实验阶段。  
> **离线可登录正版**功能并不安全，因为它绕过了正版验证。  
> 虽然做了离线、正版数据迁移，但仍然存在丢失风险。请不要频繁开关正版验证功能。
> 详细迁移与回退逻辑见 [`skills/premium.md`](skills/premium.md)。
- **国际化（I18n）**：根据客户端语言自动匹配消息，目前内置中英文
- **数据库**：支持 SQLite 与 MySQL 存储数据（HikariCP 连接池）

## 命令

### 玩家命令

| 命令                                | 别名               | 说明                                                                         |
|-------------------------------------|--------------------|------------------------------------------------------------------------------|
| `/login <密码>`                     | `/l`               | 登录                                                                         |
| `/register <密码> <确认密码>`       | `/reg`             | 注册                                                                         |
| `/changepassword <旧密码> <新密码>` | `/changepw`、`/cp` | 修改密码                                                                     |
| `/addpassword <新密码> <确认密码>`  | `/addpw`           | 为无密码账户设置密码                                                         |
| `/removepassword <验证码>`          | `/removepw`、`/rmpw` | 移除密码成为无密码账户（已绑定 2FA 需验证码确认）                            |
| `/2fa setup`                        | `/totp setup`      | 生成密钥，开始绑定双因素认证                                                 |
| `/2fa confirm <验证码>`             | `/totp confirm`    | 验证码确认，完成绑定                                                         |
| `/2fa disable <验证码>`             | `/totp disable`    | 关闭双因素认证                                                               |
| `/2fa <验证码>`                     | `/totp`            | 登录时的双因素验证                                                           |
| `/logout`                           | —                  | 登出                                                                         |
| `/unregister <密码> <验证码>`       | —                  | 自助注销：凭据验证后 `/unregister confirm` 二次确认（60 秒内），正版账户免验 |
| `/upgrade`                          | —                  | 将离线账号升级为正版账号（需开启升级功能）                                   |
| `/downgrade`                        | —                  | 将正版账号降级为离线账号（需已设密码或绑定 2FA）                             |

### 管理员命令（权限 `hsauth.admin`）

| 命令                                     | 说明                                                        |
|------------------------------------------|-------------------------------------------------------------|
| `/hsauth reload`                        | 重载配置和语言文件（数据库配置变更时需重启）                |
| `/hsauth accounts <玩家>`               | 查询该玩家 IP 下的其它账号                                  |
| `/hsauth forcelogout <玩家>`            | 强制登出                                                    |
| `/hsauth forcechangepw <玩家> <新密码>` | 强制改密                                                    |
| `/hsauth forcelogin <玩家>`             | 强制登录                                                    |
| `/hsauth forcermpw <玩家>`              | 强制清空密码转为无密码账户（无 2FA/正版时该玩家将无法登录） |
| `/hsauth forceregister <玩家> <密码>`   | 强制注册                                                    |
| `/hsauth reset2fa <玩家>`               | 强制解除双因素认证                                          |
| `/hsauth unreg <玩家>`                  | 删除账号                                                    |

## 配置

详见 [`config.yml`](src/main/resources/config.yml)，注释很全

## 开发者 API

通过 `HSAuthApi.getInstance()` 获取 API 实例（插件未加载时返回 `null`）：

```java
HSAuthApi api = HSAuthApi.getInstance();
if (api != null && api.isAuthenticated(player)) {
    // 玩家已登录
}
```

主要能力：

- **状态查询**：`isAuthenticated` / `isRegistered` / `isPremium`
- **强制操作**：`forceLogin` / `forceLogout` / `forceRegister` / `unregister`
- **玩家信息**：`getLastLocation` / `getLastIp` / `getLastLoginTime` / `getRegisteredUuids`
- **密码操作**：`checkPassword` / `changePassword`

事件（`org.howsauth.plugin.api.event` 包）：

| 事件                     | 触发时机                                               |
|--------------------------|--------------------------------------------------------|
| `HSAuthLoginEvent`      | 登录成功（密码 / IP 免密 / 正版免密 / 强制登录）       |
| `HSAuthRegisterEvent`   | 注册成功（强制注册离线玩家时 `getPlayer()` 为 `null`） |
| `HSAuthLogoutEvent`     | 登出 / 强制登出                                        |
| `HSAuthUnregisterEvent` | 删除账号                                               |
| `HSAuthLoginFailEvent`  | 登录失败（密码错误）                                   |

## PlaceholderAPI 变量

安装 PlaceholderAPI 后可用：

| 变量                      | 说明                       |
|---------------------------|----------------------------|
| `%hsauth_is_logged_in%`  | 是否已登录（`yes` / `no`） |
| `%hsauth_is_registered%` | 是否已注册（`yes` / `no`） |

## 语言文件

插件目录下 `lang/` 文件夹存放语言文件（如 `zh_CN.properties`、`en_US.properties`）。  
添加 `<语言>.properties` 即可支持更多语言。

## 开源协议

[GPL-3.0](LICENSE)
