# ConfigManager 拆分方案

> **状态：已实施完成（2026-10-06）**。全部四个阶段落地，全量测试通过（15 类 / 111 测试 / 0 失败）。
> 实际结果与本方案的偏差见文末「实施记录」。
> 目标：把 `ConfigManager` 从"714 行 / 86 个 getter 的数据桶"拆成按领域划分的配置类，使配置项的所有权、校验与调用点就近可见。

## 1. 现状

`src/main/java/org/howsauth/plugin/config/ConfigManager.java`：

| 指标 | 数值 |
|---|---|
| 总行数 | 714 |
| 私有字段 | 88（+ `plugin`、`configDirty`） |
| public getter | **86**（另有 `load()` / `reload()` 两个非取值方法） |
| loader 方法 | 10（`loadDatabaseConfig` 等） |
| 全仓库调用点 | **208**（= 86 个 getter 的 198 处 + `load`/`reload` 的 10 处），分布在约 35 个文件 |

值得注意：**代码里其实早就分好组了**。字段区、loader 区、getter 区各自都有一套完全一致的领域注释（`// 数据库设置`、`// 登录设置`、`// 正版验证` …）。拆分不是重新设计，而是把已有的隐式边界显式化。

### 1.1 真正的维护痛点（不只是行数）

1. **一个文件承载 14 个独立领域**：改 2FA 默认值要跨越 700 行定位，字段区、loader、getter 三处分离，容易漏改。
2. **loader 边界与字段/取值方边界不一致**：`loadProtectionConfig` 同时装载了 `prevent.*`、`pos.*`、`gamemode`、`blindness`、`inventory`、`reject-no-auth-account` 六个互不相干的开关，而 `prevent.*` 有独立子段。调用方里 `prevent.*` 集中在 `GuestListener`（20 处），与 `pos.*`/`gamemode` 的使用者完全不重叠（`LogoutLocation`、`PlayerListener`）。
3. **`load()` 自身是协调器**：`settings.*`、`advanced.*`、`i18n` 内联在 `load()` 里，没有对应的 loader 与字段区注释，形成"第三套分组"。
4. **校验逻辑散落且重复**：`clampInt` / `clampRange` 是通用工具，但 `databaseType`、`protection.pos.mode` 的枚举校验、`twoFaQrUrl` 的模板处理、镜像 URL 列表与 HTTP 代理列表的解析（`parseHttpProxy`）各写各的。
5. **私有反射在测试里形成隐式契约**：测试通过字段名注入配置值（见 §5），字段一搬家测试就断。

## 2. 目标架构

一个原则：**按"取值方"划分，而不是按 loader 划分**。谁读这些键，就归到同一个类里。

所有新类放在 `org.howsauth.plugin.config` 包下，**不嵌套**（避免 `ConfigManager.Premium.Session` 这类深路径），共 12 个，覆盖 86 个 getter / 198 个调用点。

### 2.1 组 A — 零或极少测试注入，可安全先拆

| 新类 | 归属键 | getter 数 | 调用点 | 涉及文件 | 测试注入 |
|---|---|---|---|---|---|
| `PremiumConfig` | `premium.*` | 16 | 25 | 9 | 无 |
| `PreventConfig` | `protection.prevent.*` | 7 | 20 | 1 | 无 |
| `DatabaseConfig` | `database.*` | 8 | 12 | 2 | 无 |
| `ProtectionPosition` | `protection.pos.*` | 8 | 10 | 2 | `protectionPosEnabled`（1 处） |
| `MessagesConfig` | `messages.*` | 7 | 8 | 1 | 无 |
| `PasswordConfig` | `password.*` | 5 | 34 | 6 | `bcryptCost`（6 处） |
| `RegisterConfig` | `register.*` | 2 | 9 | 6 | `maxAccountsPerIp`（1 处） |

### 2.2 组 B — 需要处理测试注入

| 新类 | 归属键 | getter 数 | 调用点 | 涉及文件 | 测试注入 |
|---|---|---|---|---|---|
| `LoginConfig` | `login.*` | 14 | 33 | 11 | 4 个 fail-protection 字段 |
| `TwoFactorConfig` | `login.2fa.*` | 7 | 16 | 4 | 无 |
| `PearlConfig` | `pearl.*` | 2 | 10 | 2 | `pearlEnabled`、`pearlReturnMode` |
| `ProtectionMiscConfig` | `protection.gamemode` / `.inventory` / `.blindness` / `.reject-no-auth-account` | 4 | 10 | 7 | 无 |
| `SettingsConfig` | `settings.*` + `advanced.*` | 6 | 11 | 8 | `debug`、`danglingCheck` |

### 2.3 明细：字段 → 归属类

> 行号为拆分前 `ConfigManager.java` 的字段声明位置，便于对照迁移。

**`DatabaseConfig`** — 30-37 + `databaseFingerprint`(39)
`databaseType` / `mysqlHost` / `mysqlPort` / `mysqlDatabase` / `mysqlUsername` / `mysqlPassword` / `mysqlParams` / `poolSize`
> `databaseFingerprint` 是"检测 reload 是否需要重启"的**派生值**，不是配置项。建议留在 `ConfigManager`，由 `DatabaseConfig` 提供 `fingerprint()` 计算。

**`LoginConfig`** — 42-62
`loginTimeout()` / `registerTimeout()` / `kickOnTimeout()` / `failProtectionEnabled()` / `failMaxAttempts()` / `failKickDuration()` / `failProtectionResetSeconds()` / `sessionEnabled()` / `sessionExpireMinutes()` / `loginRemindInterval()` / `loginRemindMethod()` / `loginDialogEnabled()` / `dialogAllowRiskyVersions()` / `ipChangeNotifyEnabled()`

> `registerTimeout` 虽然键名在 `register.timeout`，但取值方是登录/注册超时统一逻辑（`PlayerListener.scheduleLoginTimeout`），与 `LoginConfig` 同源，放这里。

**`TwoFactorConfig`** — 64-76
`twoFaEnabled()` / `twoFaTempSecretExpireSeconds()` / `twoFaSessionEnabled()` / `twoFaSessionExpireMinutes()` / `twoFaQrEnabled()` / `twoFaQrUrl()` / `twoFaServerName()`
> 调用方（`TwoFactorAuth`、`TwoFactorCommand`、`DialogManager`）全部按 `twoFa*` 前缀取值，边界天然清晰。`twoFaServerName()` 内含 trim 与空值兜底，迁移时保持"取值即加工"语义不变。

**`PasswordConfig`** — 79-83
`minPasswordLength` / `maxPasswordLength` / `passwordHashAlgorithm` / `bcryptCost` / `passwordPattern`

**`RegisterConfig`** — 86-88
`maxAccountsPerIp` / `ipLimitRejectJoin`

**`PreventConfig`** — 91-97
`preventMove` / `preventLook` / `preventChat` / `preventCommand` / `commandWhitelist` / `preventWorldInteraction` / `preventInventory`

**`ProtectionPosition`** — 100-107
`protectionPosEnabled` / `protectionPosMode` / `protectionPosSpawnRadius` / `protectionPosFixedX` / `protectionPosFixedY` / `protectionPosFixedZ` / `protectionPosFixedYaw` / `protectionPosFixedPitch`
> 建议同时引入 `fixedLocation(World)` 工厂，把 `findSafeAuthSpawn` 里那 7 个 `protectionPosFixed*()` 拼 `Location` 的代码收进来。

**`ProtectionMiscConfig`** — 109-115
`protectionGamemodeEnabled` / `protectionInventoryEnabled` / `protectionBlindnessEnabled` / `rejectNoAuthAccount`

**`PearlConfig`** — 117-119
`pearlEnabled` / `pearlReturnMode`（`pearlReturnEntity()` 可由 `pearl().returnEntity()` 提供）

**`SettingsConfig`** — 122-132 + 213-216
`realUnreg` / `allowSelfUnregister` / `debug` / `danglingCheck` / `purgeEnabled` / `purgeDays`

**`MessagesConfig`** — 169-181
`joinDisabled` / `quitDisabled` / `joinHideUnauthenticated` / `joinDelayUntilAuthenticated` / `joinMessageTemplate` / `quitHideUnauthenticated` / `quitMessageTemplate`

**`PremiumConfig`** — 135-167（16 个 getter，最大的一块）
`premiumEnabled()` / `premiumAutoVerify()` / `premiumHttpProxies()` / `premiumSessionServerCandidates()` / `premiumTimeoutSeconds()` / `premiumVerifyDeadlineMs()` / `premiumCrackerCacheSeconds()` / `premiumHandshakeTimeoutMs()` / `premiumMaxRetries()` / `premiumRetryIntervalMs()` / `premiumHttpPoolSize()` / `premiumCacheCap()` / `premiumUpgradeEnabled()` / `premiumDowngradeEnabled()` / `premiumPasswordFallbackEnabled()` / `premiumFallbackCacheSeconds()`

> 注意命名不对称：字段是 `premiumSessionServerMirrors`，getter 却是 `premiumSessionServerCandidates()`（内部做了 trim/去空并缓存）。迁移时**以 getter 为迁移单位**，字段名可顺便对齐为 `mirrors` 或 `candidates` 之一，避免下一个维护者再踩一次。

### 2.4 保留在 `ConfigManager` 的职责（协调者，不再是数据桶）

| 职责 | 现有成员 | 说明 |
|---|---|---|
| 版本与增量合并 | `checkConfigVersion`、`mergeMissingKeys`、`majorMinor`、`LANG_RESOURCES` | 涉及 `plugin.saveResource` / `I18n.reload` 副作用 |
| 生命周期编排 | `load()`、`reload()`、构造器 | 按序构造各配置类 |
| 跨领域写回 | `configDirty` | 被各校验分支置位，`load()` 末尾统一 `saveConfig()` |
| DB 变更检测 | `databaseFingerprint` | 派生于 `DatabaseConfig`，用于 reload 判定 |
| i18n 引导 | `I18n.setDefaultLocale` / `setClientLanguageDetection` | `load()` 内联 |

### 2.5 目标调用写法

```java
// 现在
plugin.getConfigManager().premiumEnabled()
plugin.getConfigManager().danglingCheck()
plugin.getConfigManager().protectionPosEnabled()

// 拆分后
plugin.config().premium().enabled()
plugin.config().settings().danglingCheck()
plugin.config().protectionPosition().enabled()
```

同时把 `HowSAuth.getConfigManager()` 改名为 `config()`（可选，但能让所有调用点受益于统一的短前缀）。按此方案 **全部 208 个调用点都落在新结构上**（198 处 getter + 10 处 `load`/`reload`；后者留在 `ConfigManager`，实际需改的是 198 处）——这是本方案的主要成本，见 §4 分阶段控制。

## 3. 可提取的共享工具（顺带收益）

| 提取物 | 来源 | 用途 |
|---|---|---|
| `ConfigNumbers.clamp(key, value, min)` / `clampRange(key, value, min, max)` | `clampInt`(565) / `clampRange`(576) | 各配置类共用，含告警文案 |
| `EnumOptions.parse(key, value, allowed, fallback)` | `databaseType`(261)、`protectionPosMode`(388) 的重复模式 | 消除枚举校验重复 |
| `EndpointList.parseProxies(List<String>)` | `parseHttpProxy`(550) | 代理列表解析独立化 |
| `EndpointList.parseUrls(List<String>)` | `premiumSessionServerMirrors`(687) 的 trim/去空逻辑 | 镜像 URL 列表 |

**注意**：`parseHttpProxy` 与镜像 URL 解析目前没有单元测试覆盖（`ConfigValidationTest` 只覆盖部分校验路径）。提取时应同步补测试，否则会把无测试逻辑搬来搬去。

## 4. 分阶段迁移

每阶段结束都必须满足：`./gradlew test` 全绿 + 全量编译。

### 阶段 1（低风险，验证模式可行）
`PremiumConfig` + `PreventConfig` + `DatabaseConfig` + `ProtectionPosition`
- 特征：**无测试注入**（除 `protectionPosEnabled` 1 处）、纯读取、无跨字段校验。
- 改动面：67 个调用点 / 约 12 个文件。
- 验证：`MojangClient`、`ConnectionHandler`、`DataService`、`GuestListener`、`AuthManager` 等编译通过。
- 顺带处理：`premiumHttpProxies` / `premiumSessionServerCandidates` 的列表解析（`parseHttpProxy`、镜像 URL trim/去空）——这是本阶段唯一有实际逻辑的部分，补测试后再搬。

### 阶段 2（纯机械，几乎无测试耦合）
`MessagesConfig` + `PasswordConfig` + `RegisterConfig`
- 特征：仅 `bcryptCost`（6 处）与 `maxAccountsPerIp`（1 处）需要迁移注入点。
- 改动面：51 个调用点。

### 阶段 3（触及测试注入，需同步处理 §5）
`LoginConfig` + `TwoFactorConfig` + `PearlConfig` + `ProtectionMiscConfig` + `SettingsConfig`
- 改动面：80 个调用点，且覆盖 8 处测试注入（`failProtection*` 4 个字段、`debug`、`danglingCheck`、`pearlEnabled`、`pearlReturnMode`）。
- 这是风险最集中的一阶段，建议单独一个提交，便于回滚。

### 阶段 4（收尾）
- 从 `ConfigManager` 删除全部 86 个 getter 与 88 个字段。
- 若采纳 `getConfigManager()` → `config()` 改名，在同一提交内完成。
- 更新 `config.yml` 注释中提及"某 getter"的表述（现有注释未提 getter，预计无改动）。

## 5. 关键风险：测试的私有反射注入

`MockBukkitHarness.inject`（`MockBukkitHarness.java:255`）用：

```java
Field field = declaring.getDeclaredField(fieldName);  // 只在本类查找，不遍历父类
```

**字段一旦搬进子配置对象，`getDeclaredField` 会抛 `NoSuchFieldException`，测试直接失败。** 当前受影响的注入点共 **11 个字段 / 27 处、分布在 7 个测试文件**：

| 字段 | 迁往 | 处数 | 测试文件 |
|---|---|---|---|
| `bcryptCost` | `PasswordConfig` | 6 | DebugModeTest / FailProtectionResetTest / FailProtectionTest / LongRunStabilityTest / QuitFlowRegressionTest / SpectatorChunkProtectionTest |
| `debug` | `SettingsConfig` | 6 | DebugModeTest |
| `failProtectionEnabled` | `LoginConfig` | 3 | FailProtectionResetTest / FailProtectionTest / LongRunStabilityTest |
| `failMaxAttempts` | `LoginConfig` | 2 | FailProtectionResetTest / FailProtectionTest |
| `failKickDuration` | `LoginConfig` | 2 | FailProtectionResetTest / FailProtectionTest |
| `failProtectionResetSeconds` | `LoginConfig` | 2 | FailProtectionResetTest / FailProtectionTest |
| `pearlEnabled` | `PearlConfig` | 2 | PearlRuntimeTest |
| `pearlReturnMode` | `PearlConfig` | 1 | PearlRuntimeTest |
| `protectionPosEnabled` | `ProtectionPosition` | 1 | SpectatorChunkProtectionTest |
| `maxAccountsPerIp` | `RegisterConfig` | 1 | LongRunStabilityTest |
| `danglingCheck` | `SettingsConfig` | 1 | SpectatorChunkProtectionTest |

### 两个选项

**选项 A（推荐）：让 inject 递归查找**
在 `MockBukkitHarness` 加一个递归辅助：先 `getDeclaredField`，失败则遍历目标的嵌套字段再找。测试调用点**零改动**，且保留了"测试要覆盖校验结果"的表达力。
- 代价：隐式链接仍在（字段改名照样断），但字段改名本来就会编译报错。
- 约 15 行。

**选项 B：把注入改成指向新类**
`inject(LoginConfig.class, "failMaxAttempts", config.login(), …)`。
- 更显式，但要改全部 27 处注入调用，且每拆一个类都要改。

建议阶段 1、2 用选项 A 先跑通（阶段 1、2 其实几乎不依赖注入），阶段 3 再决定是否顺手改成选项 B。

### 另一处构造点
`MockBukkitHarness.java:116` 直接 `new ConfigManager(plugin)`，`ConfigValidationTest.java:119` 同样。构造器签名保持 `ConfigManager(HowSAuth)` 不变，这两处无需改动。

## 6. 校验与回归

每阶段执行：

```powershell
$env:GRADLE_USER_HOME = "C:\Users\Sberban\Documents\work\HowSAuth\.gradle-local"
.\gradlew.bat test --console=plain --offline
```

当前基线：**80 个测试 / 0 失败**（14 个测试类，统计自 `build/test-results/test/*.xml`）。
另需留意：本机沙箱下 `build\reports\problems\problems-report.html` 可能因 `Archive` 属性写入失败，导致构建以 `AccessDeniedException` 收尾——**这是既有环境问题，与测试结果无关**，判断是否通过请以测试任务输出或 XML 统计为准。

重点回归项：
- `ConfigValidationTest`（`shippedDefaultsAreSafe` / `invalidValuesFallBackToSafeDefaults`）——直接反映默认值与校验回退，是拆分是否改变行为的第一道防线。
- `SpectatorChunkProtectionTest`——覆盖最近加入的 `danglingCheck` 与预载守卫。
- `DebugModeTest`——`debug` 与 `bcryptCost` 注入量最大，能验证注入机制是否仍工作。

## 7. 顺带发现（非本次范围，建议记录）

1. **`reload()` 语义不统一**：`debug` / `danglingCheck` 注释都写"可用 `/hsauth reload` 生效"，但部分键（如 `protection.pos.*` 的出生半径）在玩家已进服后不会追溯生效。建议在 `config.yml` 顶部统一说明"哪些键需要重启"。
2. **无测试逻辑**：`parseHttpProxy`、镜像 URL 列表解析、`databaseFingerprint` 拼接没有直接测试。拆分时补上。
3. **`twoFaServerName` 的 trim 与空值兜底**写在 getter 里（`ConfigManager.java:623`），这类"取值时加工"逻辑拆分后应保持在同一处，避免调用方各自 trim。

## 8. 预期收益

| 指标 | 现在 | 拆分后 |
|---|---|---|
| `ConfigManager` 行数 | 714 | 约 150（协调者） |
| `ConfigManager` getter 数 | 86 | 0 |
| 单个最大配置类 | — | `PasswordConfig` 约 60 行 / 5 getter；`PremiumConfig` 约 70 行 / 16 getter |
| 定位一个配置项 | 跨 700 行、3 处分离 | 单文件内字段 + 校验 + 取值一体 |
| 校验逻辑复用 | `clampInt`/`clampRange` + 3 处手工枚举校验 | 共享工具类 |
| 改动面 | — | 198 个 getter 调用点 + 10 处 `load`/`reload`（一次性成本） |

> 数值说明：本文件所有数字均经逐名核对（86 个 getter 全部列出并与 12 个目标类一一对应；208 = 198 个 getter 调用点 + 10 处 `load`/`reload`）。`premium` / `login` 等类的 getter 数曾因正则把多行方法一并计入而虚高，已修正。

---

# 实施记录

## 实际结果

| 指标 | 拆分前 | 拆分后 |
|---|---|---|
| `ConfigManager` 行数 | 714 | **173** |
| 其 public 访问器 | 86 getter + `load`/`reload` | **15**（12 个领域访问器 + `load`/`reload` + 构造器） |
| 其私有字段 | 88（+`plugin`/`configDirty`） | **13**（12 个领域对象 + `plugin`） |
| 领域配置类 | 0 | **12**（见下表） |
| 支撑类 | — | `ConfigFixer`（修正记录器） |
| 测试 | 15 类 / 80 测试 | **15 类 / 111 测试 / 0 失败** |

新增文件（`src/main/java/org/howsauth/plugin/config/`）：

| 文件 | 行数 | 访问器 | 覆盖键 |
|---|---|---|---|
| `PremiumConfig` | 200 | `premium()` | `premium.*` |
| `LoginConfig` | 130 | `login()` | `login.*` + `register.timeout` |
| `DatabaseConfig` | 101 | `database()` | `database.*` |
| `PasswordConfig` | 99 | `password()` | `password.*` |
| `PreventConfig` | 71 | `prevent()` | `protection.prevent.*` |
| `TwoFactorConfig` | 70 | `twoFactor()` | `login.2fa.*` |
| `ProtectionPosition` | 67 | `protectionPosition()` | `protection.pos.*` |
| `MessagesConfig` | 72 | `messages()` | `messages.*` |
| `SettingsConfig` | 63 | `settings()` | `settings.*` + `advanced.*` |
| `ProtectionMiscConfig` | 48 | `protectionMisc()` | `protection.gamemode/.inventory/.blindness/.reject-no-auth-account` |
| `PearlConfig` | 38 | `pearl()` | `pearl.*` |
| `RegisterConfig` | 32 | `register()` | `register.*` |
| `ConfigFixer` | 75 | — | 修正记录器（`clampMin`/`clampRange`/`set`/`warn`/`dirty`） |

## 最终调用形态

```java
// 旧
plugin.getConfigManager().premiumMaxRetries()
plugin.getConfigManager().failMaxAttempts()
plugin.getConfigManager().minPasswordLength()
// 新
plugin.config().premium().maxRetries()
plugin.config().login().failMaxAttempts()
plugin.config().password().minLength()
```

两处命名约定在实施中确立并全量贯彻：

1. **访问器去类名前缀**——类名已提供上下文，故 `PremiumConfig.premiumMaxRetries()` 落为 `premium().maxRetries()`。例外的只有 `login().registerTimeout()`（键在 `register` 段但归属登录窗口）与 `login().failProtectionResetSeconds()` 这类无冗余前缀可去的名字。
2. **`HowSAuth` 上的 `getXxx()` 一律去 `get` 前缀**——项目原有 `sessions()`/`accounts()`/`locations()`/`loginFlow()`/`twoFactor()`/`failProtection()`/`playerFiles()` 已是短名，本次把剩下的 6 个旧式访问器统一：`getConfigManager()`→`config()`、`getPlayerDataManager()`→`playerData()`、`getAuthManager()`→`auth()`、`getPlayerListener()`→`playerListener()`、`getPendingPearlManager()`→`pendingPearls()`、`getPreJoinAuthListener()`→`preJoinAuth()`。

## 与方案的偏差（如实记录）

1. **`ProtectionMiscConfig` 的键范围**：方案写作"gamemode / inventory / blindness / reject-no-auth-account"四项，实施一致；但它**没有 fixer**（四项均无校验）。
2. **`configDirty` 被删除**：拆分过程中所有 `configDirty = true` 直写点都随校验代码迁入 `ConfigFixer`，该字段失去唯一写入者。故删除该字段，`load()` 末尾只判 `fixer.dirty()`。原实现"一次加载最多写一次盘"的行为不变。
3. **`DEFAULT_QR_URL` 常量移入 `TwoFactorConfig`**，`ConfigManager` 不再持有该常量（全仓库单一处定义）。
4. **新增 `ProtectionPosition.fixedMode()` 与 `fixedLocation(World)`**：把 `findSafeAuthSpawn` 里"判 fixed 模式 + 拼 7 个坐标字段"的代码收进配置类，调用方不再了解配置键布局。
5. **测试反射注入的迁移方式**：方案 §5 提出的"选项 A（递归 inject）"未采用，改为**选项 B**——27 处注入的第一个参数改为真正声明字段的类，目标改为 `env.config().settings()` 这类子对象。理由：更显式，且实施中发现 `getDeclaredField` 的语义正好能充当"字段搬错了没有"的校验。
6. **`@SuppressWarnings("BooleanMethodIsAlwaysInverted")` 及"保持 preventXxx 正向命名"注释被删除**：它约束的布尔 getter 已全部迁出 `ConfigManager`，类内不再有布尔 getter。
7. 方案 §3 提出的 `ConfigNumbers`/`EnumOptions` 两个工具类**未单独建**：数值钳制收敛为 `ConfigFixer.clampMin`/`clampRange`；枚举回退各领域文案与 i18n key 不同（`log.config_mode_invalid` / `log.config_database_type_invalid` / `log.config_hash_invalid` / `log.password_*`），强行统一会改变告警文案，故保留各自的 `warn` + `set` 组合。

## 验证

```powershell
$env:GRADLE_USER_HOME = "C:\Users\Sberban\Documents\work\HowSAuth\.gradle-local"
.\gradlew.bat clean test --console=plain --offline
```

干净重建结果：**15 个测试类 / 111 测试 / 0 失败 / 0 错误 / 0 跳过**，与拆分前基线（80 测试）相比新增 31 个配置解析测试且无删减。

新增测试 `ConfigParsingTest`（31 个用例）覆盖此前无测试的逻辑：`parseHttpProxy` 格式/端口边界/多冒号/trim、镜像 URL 协议校验与末尾斜杠归一、候选列表官方端点恒在首位与防御性拷贝、`clampMin`/`clampRange` 的钳制与**回写语义**、枚举回退（database.type/pos.mode/password.hash）、密码正则回退、命令白名单归一、`databaseFingerprint` 的 `reload()` 判定。

## 实施中查清的两个测试环境约束（对后续测试有用）

1. **YAML 序列化会重排引号与列表缩进**，因此"配置文件是否被回写"不能用字节比较，只能比较语义值（写入原始值 → 断言读回的语义值）。
2. **`JavaPlugin.reloadConfig()` 在文件修改时间未变时不重读磁盘**（MockBukkit 不覆盖该实现），而 `ConfigManager.reload()` 依赖它。测试中写文件后需显式前推 mtime，否则断言会依赖文件系统时间戳精度。

## 顺带发现（未处理，建议后续跟进）

1. **`reload()` 生效语义不统一**：`advanced.debug` / `advanced.dangling-check` 的注释写"可用 `/hsauth reload` 生效"，但 `protection.pos.*` 等键在玩家已进服后不追溯生效。建议在 `config.yml` 顶部统一说明哪些键需要重启。
2. **`parseHttpProxy` 与镜像 URL 解析现已补测试**（原方案 §3 提到的问题已解决）；`databaseFingerprint` 的拼接也已通过 `reload()` 逐字段用例覆盖。
3. `twoFaServerName` 的 trim 与空值兜底在 getter 内，迁移后落在 `TwoFactorConfig.serverName()`，调用方无需再各自 trim——这一约定已保持。
