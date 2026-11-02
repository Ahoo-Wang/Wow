# 服务端测试与本地运行的通用模式

日期：2026-11-02。基线：`origin/main` `46c9922aaa`。状态：已采纳（D389，用户 2026-11-02 按推荐），待实现。上位文档：[进度](progress.md)、[决策台账](decisions.md)。

## 1. 起因

用户要求从 eShop 中抽出通用模式，提升复用率、易用性与开发效率，并点名 `wowServer` 的测试写法。eShop 按「用户只读公开文档与 Skill」的方式写成（D159），它反复手写的东西，就是每个用户都会手写的东西。

领域代码已经由测试 DSL（`aggregateSpec`、处理器规范）覆盖，没有值得抽出的重复。重复集中在服务端测试与多服务运行上。五个服务的 18 个服务端测试文件里：

| 写法 | 出现 |
| --- | --- |
| 手写路径与 JSON 字符串调 HTTP（`server.http.post("/eshop-basket/basket/…", """{…}""")`） | 7 个文件，26 次 |
| 手拼开发身份头（`mapOf(USER to …, OWNER to …, ROLES to …)`） | 8 个文件，25 处 |
| 每个测试自定义状态码常量（`const val OK = 200`） | 10 个文件 |
| 取错误码断言（`body!!["code"].asString()`） | 8 个文件，24 处 |
| 轮询等异步结果（`eventually` 与 `Thread.sleep`） | ordering、payment 各复制一份，6 个文件共 29 处 |
| 内存主题的测试 bean（`InMemoryTopics`、`SourceTopics`） | 4 个文件 |
| 假的对端服务（`FakeOrdering`：自起 HTTP 服务器，记录调用） | 105 行 |
| 一个 JVM 里跑整个 eShop（`EshopLocal`） | 142 行 |

框架里已有能消掉这些重复的部件，测试却没用上：
- 类型化的 Kotlin 客户端 `WowClient`（`edge/client`：`CommandClient` 与 `QueryApi`，按 api 模块里的命令与状态类收发，用户模式以 `UserCredential` 把 `Caller` 写成请求头），平时只用于服务之间的调用；
- 能等到 Saga 链尾的等待计划（`CommandClient.wait`、`stream`）；
- `wow.transport=memory`（DX 缺口 G27）。

## 2. 决定（D389）

### ① `wowServer` 给出类型化客户端（`test-spring-boot`）

- `WowServer` 增加按调用者取得的客户端：`commands(caller)` 是绑定其公开端口的 `CommandClient`，`queries(caller)` 是 `QueryApi`。它们与对端调用用的是同一套实现，由 `WowClient` 构建，测试据此按命令类发送、按状态类读取，不再写路径与 JSON。
- `edge/client` 增加开发身份的 `UserCredential`：把 `Caller` 写成 D334 的开发身份头（`Wow-User`、`Wow-Owner`、`Wow-Roles` 等），即宿主的 `DevelopmentCallerResolver` 读取的那组头。头名只定义一处，两边共用，不复制常量。
- 异步结果优先用等待计划等到链尾。只有等待计划等不到的效果（例如假对端是否收到调用）才轮询，由 `test-spring-boot` 提供一个有界的 `eventually`，各测试不再自写。
- 被拒的命令经客户端以 `CommandRejectedException`、`WowProblemException` 带回错误码。`Answer` 增加类型化的 problem（状态、错误码、违规项），供仍走 `server.http` 的测试断言。
- `server.http` 保留，用于测线上契约本身：路由形状、请求头、problem+json。

### ② 假对端 `fakePeer`（`test-spring-boot`）

- 起一个模拟对端内部端口调用路由（`callRoutes`）的桩，记录收到的每次调用：解码后的命令、调用携带的 scope 与 operator、`Wow-Service`。默认答成功，可按规则答拒绝或超时。
- `wowServer` 接受对端：自动写好 `wow.peers.<context>.url`。
- 编码与解码复用 `:http`、`:client` 已有的编解码，不手写线上格式。凡是测跨服务 Saga 或同步调用的用户都需要它，eShop 的 `FakeOrdering` 是第一个用户。

### ③ 测试的内存主题

先查清 eShop 那 4 个文件为什么手写 `InMemoryTopics` 与 `SourceTopics` 的 bean（分区数、确定性，还是需要直接读写主题），再定：能由配置表达的，改为配置项（`wow.transport=memory` 等）；确需直接操作主题的，由 `test-spring-boot` 给出一行可用的入口。不在每个测试里声明 bean。

### ④ 本地多服务运行器（`test-spring-boot`，在 ① 之后）

把 `EshopLocal` 泛化：多个服务在一个 JVM 里，各自以 `wow.contexts.include` 只服务自己的上下文，共用一份内存主题（发往别的上下文的命令、发布流、命令主题都在其中流转），端口固定或随机，全部就绪后打印一行端口清单，可选在标准输入关闭时退出。用途：本地开发、前端联调、演示，并写进快速开始的「本地运行多个服务」。eShop 的 `EshopLocal` 改用它。

### ⑤ Gradle 插件约定 `integrationTest`（DX 缺口 G17）

`me.ahoo.wow.service` 插件按约定注册名为 `integrationTest` 的 JVM 测试套件（Gradle 的 JVM Test Suite），`check` 不依赖它（与仓库的约定一致：容器测试显式运行）。不替用户添加 Testcontainers 或任何依赖，只给出源码集与任务。快速开始与测试页面写明。

### ⑥ eShop 改用以上部件

①～④ 合并后，eShop 的服务端测试与 `EshopLocal` 改用它们，作为这些 API 的第一个用户，检验其易用性。改不顺手的地方回到 ①～④ 修正，而不是在 eShop 里绕开。每个服务各测一遍 springdoc 与 Swagger UI 的用例，只留一处。

### 不抽象

- **多 JVM 加容器的整栈编排**（`EshopService`、`EshopStack`，576 行：每个服务一个 JVM、各自的 PostgreSQL 库、Kafka、ClickHouse）：与基础设施绑得太紧，通用版本价值有限，列入[路线图](roadmap.md) §6「10.x 候选」，先在文档里给出配方。
- **服务的 `application.yaml`**：各服务之间的差异是服务自身的（端口、对端、主题），已足够精简。

## 3. 范围与顺序

- ①、②、⑤ 改的是已发布模块的公开 API（`test-spring-boot`、`client`、`gradle-plugin`），GA 后冻结，所以在 GA 前做。④ 写进快速开始，也在 GA 前做。均为加法，不涉及线上格式。
- 派工按文件分：一个子会话依次做 ①、③、②、④（同在 `test-spring-boot`，按顺序分 PR）；另一个子会话并行做 ⑤（`compiler/gradle-plugin`）；⑥ 在 ①～④ 合并后派。
- 用户文档：①～④ 更新测试页面与快速开始（中英文结构一致）；⑤ 只改测试页面里关于容器测试的一段与快速开始的构建脚本。两者先后合并，后者合并前基于最新 main 重做文档改动，避免冲突。
