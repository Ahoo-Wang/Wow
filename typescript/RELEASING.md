# 发布手册

Maven 和 npm 用同一个版本号、同一个 `v*` tag 一起发布（见 [MIGRATION.md](MIGRATION.md)「发布策略」）。本文是维护者照做的手册：发布流水线怎么把关、首发怎么做、日常发版和出错时怎么处理。

**9.2.0 已发布**（2026-10-03，tag `v9.2.0` → `7fe4934b5`，[GitHub release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.0)）：五个发布包在 npm 上的首发，首发清单只剩 E 第 5 步与 F 第 3 步。它的发布说明就是那个 release，草稿已删。此后的补丁：

- **9.2.1**（2026-10-04，[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.1)）：服务端渲染修复、fetcher peers 放宽到 `^5.1.5 || ^6.0.0`、React peer 下限 `^19.0.0`。
- **9.2.2**（2026-10-04，[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.2)，[Packages Deploy](https://github.com/Ahoo-Wang/Wow/actions/runs/37180601238)）：视图引擎的稳健性修复与 Kotlin ABI 基线。`npm-smoke` 第一次两路都超时——npm 接受 wow-client 之后约 13 分钟才对外提供，当时等待上限是 5 分钟；重跑后全绿。之后等待上限改为 30 分钟，`npm-deploy` 也不再需要审批，改为等 Maven 两路成功（#3908）。

**9.3.0 已发布**（2026-10-09，tag `v9.3.0` → `e71384074`（#4025 的合并提交），[GitHub release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.3.0)，[Packages Deploy](https://github.com/Ahoo-Wang/Wow/actions/runs/37882720912)）：整体重构的次版本，按用户决定**不发 rc**——五个发布包自 9.2.3 起源码没有变化，rc 只发 npm 验证不到 JVM 侧；C′ 改在 main 的本地构建上做（[MIGRATION.md](MIGRATION.md)「9.3.0 控制台试用记录」）。`admission`（27 个破坏性 PR 都点名）、`preflight`、`github-deploy`、`central-deploy`、`npm-deploy`（五个包都带 provenance，`latest: 9.3.0`）、两个 `npm-smoke` 全部成功；三个镜像的 `9.3.0`、`9.3`、`latest` 在 Docker Hub、ghcr、阿里云 digest 一致。发布说明就是那个 release，草稿已删。

下一个版本按「发版准入」第 3 条定：上一个 tag 以来没有破坏性改动就是下一个补丁版本；有就只能是下一个次版本（`x.Y.0`），准入拒绝补丁版本。最新发布是 `v9.5.0`（2026-10-09，[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.5.0)）。下一个版本的草稿开始写时放在 `release-notes/v<版本>.md`。

「发布包」在本文里一律指 `.github/scripts/publish-npm.mjs` 的 `PUBLISHED`，9.2.0 起是五个：wow-client、wow-react、wow-generator、wow-view-engine、wow-view-store。下面的循环都从 `node .github/scripts/publish-npm.mjs --list` 取包名，不在文中手写名单。

## 发布流水线

`package-deploy.yml` 在创建 GitHub release 时运行，也可以手动在一个 `v*` tag 上运行（Actions → Packages Deploy → Run workflow → Use workflow from → Tags → `v<version>`）。别的 ref 一律拒绝。所有 action 都按 commit SHA 锁定，Renovate 的 `github-actions` 组连同版本注释一起升级。

| Job              | 做什么                                                                                                                                                                                                                                                                                                                                                                                                          | 权限                                                                      |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `admission`      | ref 必须是 `v*` tag；tag = `gradle.properties` = 各 `package.json` 的版本；发版准入（`.github/scripts/release-admission.mjs`）                                                                                                                                                                                                                                                                                  | `contents: read`、`actions: write`、`pull-requests: read`，不接触任何密钥 |
| `preflight`      | `pnpm build:typescript` → 打出 npm tarball → 包检查（`.github/scripts/package-check.mjs`）→ `publish-npm.mjs --dry-run` → 上传 tarball；`./gradlew build allIntegrationTest`、`publishToMavenLocal`（签名）→ 上传 Maven 产物                                                                                                                                                                                    | `contents: read`                                                          |
| `github-deploy`  | 发布到 GitHub Packages                                                                                                                                                                                                                                                                                                                                                                                          | `packages: write`                                                         |
| `central-deploy` | 发布到 Maven Central                                                                                                                                                                                                                                                                                                                                                                                            | Sonatype 凭据                                                             |
| `npm-deploy`     | 等 `github-deploy`、`central-deploy` 都成功后（environment `npm-publish`，不需要审批），发布 preflight 检查过的**那几个 tarball**：不安装、不构建，npm 固定为精确版本，OIDC 可信发布并带 provenance                                                                                                                                                                                                             | `id-token: write`，environment `npm-publish`                              |
| `npm-smoke`      | `npm-deploy` 成功以后，在 Node 22.12.0 和 24 上各跑一次 `package-check.mjs --registry`：等 registry 能给出这个版本（最多 20 次、间隔 15 秒），核对 dist-tag，然后在干净项目里从 npm 装每个发布包的 `<包>@<版本>`，import 每个入口（承诺给 CommonJS 的入口再 require 一次；视图引擎与视图存储只有 ESM）、解析视图引擎的样式表、运行 generator 的 `--version` 与 `wow-view-engine theme-check --help`、做类型检查 | `contents: read`，不接触任何密钥                                          |

同一个 ref 同时只跑一次发布（`concurrency`，排队而不取消）。每个 job 都有超时。

### 发版准入

准入回答的问题是「要发出去的这个提交本身是否通过了全部检查」，所以它看的是这个提交上的**完整运行**：

1. 提交必须在 `main` 或某条维护线 `release-x.y` 上，tag 不能指向没经过 PR 的提交。
2. `typescript.yml`、`typescript-contract.yml`、`typescript-storybook.yml` 在这个提交上各有一次**手动触发（workflow_dispatch）的运行**成功，而且其中的 `typescript-gate`、`typescript-contract-gate`、`typescript-storybook-gate` 通过。手动触发的运行没有 diff 的基准，scope 会打开全部 job；push 运行只测那次 push 改到的部分，所以不算数。提交上还没有这样的运行时，准入自己在 release tag 上触发，然后等它们跑完（默认最多 90 分钟）。已有的运行（比如维护者事先触发过，或者上一次发布尝试触发的）直接复用；它失败了就要先重跑它的失败 job，再重跑这次发布。
3. 上一个 `v*` tag 以来有破坏性改动时，这次发布必须是 `x.Y.0`。破坏性改动有三种认法：提交标题带 `!`、提交正文有 `BREAKING CHANGE:` 脚注、提交所属的 PR 带 `breaking-change` 标签。标签是标题之外的那道网：pr-labeler 在标题带 `!`、PR 描述有 `BREAKING CHANGE:` 行、勾了 PR 模板的 **Breaking** 框、或者有一节标题恰好是 `## Breaking`／`## Breaking changes`（二、三级标题，可以带括号限定；`### Breaking down the work` 不算）而且正文不是 None／No／N/A 时自动加（`.github/scripts/breaking-label.mjs`）；PR 的 diff 从公开面记录里去掉或改动了一行时也自动加，这些记录是 Kotlin ABI 转储 `<模块>/api/<模块>.api`、API 报告 `typescript/*/test/api/*.api.md` 和公开名单 `typescript/*/test/surface/*.txt`。规则按行看：只有 `+` 行（新增）不加；任何 `-` 行都加，签名改动在 diff 里是一对 `-`／`+`，同样算。不算数的 `-` 行只有四种：空行、公开名单的 `#` 表头（里面的名字数随新增变化）、API 报告的 `//` 注释（发布标记和 `(undocumented)`，补 TSDoc 时会变；删掉的声明自己那一行照样算）、以及折叠空白后又作为 `+` 行出现在同一文件里的行（只是缩进或位置变了）。改名或移动的记录按它自己的 diff 判断：内容不变不加，删除整个记录等于删除每一行；GitHub 不给 patch 的超大 diff 只要有删除就加。某行的改动其实不破坏兼容时（比如只改了一条消息的文案），由维护者在最后一次 push 之后去掉标签并在 PR 里说明理由：labeler 每次 push 都会重跑、把标签加回来。评审发现作者没标的破坏时手工加。PR 描述里的 `## Behaviour changes` 一节**不加**标签：它是发布说明「Behaviour changes」的原料，那一节只放不破坏兼容的可见变化（见「发布说明」）。
4. `x.Y.0` 的 GitHub release 正文必须点名（`#编号`，HTML 注释里的不算：release 页面不显示注释）上一个 tag 以来 main 上（first parent，不含合并进来的 fetcher 历史）每个破坏性 PR；tag 还没有 release 时拒绝。漏掉的 PR 会逐条列在失败信息里：把它写进「Breaking」（只改了未发布代码的写进「Pre-release changes」那一行），编辑 release 正文后重跑 `admission`。

准入还认不出的情况，靠评审和发布说明的人工核对补上：**提交与 PR 对不上。** 准入靠 squash 合并在标题末尾加的 `(#编号)` 把提交对到 PR、再查标签。rebase 合并（ruleset 允许）的提交，或者合并时把 `(#编号)` 改掉、删掉的标题，对不到 PR，标签也就不起作用，只有提交自己的 `!` 或 `BREAKING CHANGE:` 脚注算数。带破坏的 PR 一律 squash 合并，标题里留着 `(#编号)`。

Gradle 流水线不在准入里：preflight 自己在这个提交上跑 `./gradlew build allIntegrationTest`（单元、契约、集成测试），比回头查别的运行更直接。

为什么不选「要求上一个 tag 以来 main 上每次 push 运行都成功」：push 运行是裁剪过的，拼起来也只是近似覆盖；中途失败、后来修好的运行会永远挡住发布，除非再加一套「以后面的为准」的规则；维护线没有 push 触发，这条规则在那里根本不成立。在发版提交上跑一次完整运行，对 main 和维护线都一样，也不依赖合并节奏。

## 首发清单

首发分两步：先由维护者在本机手工发 `9.2.0-rc.0`（dist-tag `next`，没有 provenance），让五个包名在 npm 上存在，才能给它们配置 Trusted Publisher；再由 CI 带 provenance 发 `9.2.0`，`latest` 从这时起指向正式版（rc 期间 `latest` 也是 rc，见 C 第 5 步）。没有配 Trusted Publisher 的包，CI 的 OIDC 发布会失败（D）。

**时机（用户 2026-09-25 定）**：发布包和 Wow 9.2.0 服务端**一起**发，不先单发；并且**等补偿控制台重构全部完成（[view-engine-rebuild.md](../compensation/dashboard/docs/design/view-engine-rebuild.md) 批 0～7，旧页面都被引擎接管；它第 2 节以后由 [console-redesign.md](../compensation/dashboard/docs/design/console-redesign.md) 取代，那份的批 1～4 也已合并，#3706～#3711）之后再发**，连 `9.2.0-rc.0` 也在那之后。用户原话：「这样能提前发现真实环境问题、验证真实 API。」控制台完成后，rc 试用（C′）只剩核对 npm 产物，真实 API 已经在控制台上验证过。

- **为什么**：包版本跟 Wow 走，文档已写「需要 Wow 9.2.0+」的能力（`BEFORE_NOW`/`AFTER_NOW`、查询配置改名、违规码），先发客户端会让用户拿不到对应的服务端。补偿控制台批 1～4 是 wow-client、wow-react 最真实的用法，它暴露的形状问题要在首发前改掉。
- **范围冻结**：从 2026-09-25 到发布，三个包只收两类改动：补偿控制台暴露的问题的修复，以及只加不改的接口（违规码、N5 能力描述符）。不再做结构性重构。
- **视图引擎一起首发**（用户 2026-09-25 定：「视图引擎完成后一起发」）：发版也等视图引擎完成。完成的判据：N5 能力描述符已接上（引擎按它做定义准入）；主题重构 D46 的各批已落地；第二轮全面审查通过。2026-10-02 核对：三条都满足（第二轮全面审查由用户 2026-10-01 明确通过），补偿控制台批 0～7 与 console-redesign 的批 1～4 也已完成。
- **视图存储也随 9.2.0 发布**（用户 2026-10-01 定，第二轮审查 R2-43，[D78](wow-view-engine/docs/design/decisions.md)）：npm 的 `@ahoo-wang/wow-view-store`，Maven 的 `wow-view-store-api`、`-domain`、`-starter`（移出 `incubatingProjects`；`wow-bom` 只约束发布的模块），`wow-view-store-server` 镜像照旧随 `v*` tag 推送。9.2.0 因此发**五个** npm 包，五个都配 Trusted Publisher，首发清单 A 的各项对五个包逐条做。

### A. 代码侧（合并到 main）

- [x] 发布工作流按 SHA 锁定 action；`npm-deploy` 使用 environment `npm-publish`；手动运行只接受 `v*` tag；`concurrency` 和超时（R3-01、R3-02、R3-14）。
- [x] 发版准入：三条 TypeScript 流水线在发版提交上的完整运行，缺了就自动触发（R3-03）。
- [x] 包检查：publint、node16/nodenext/bundler 下的类型、干净项目里安装导入并运行 bin，在 `typescript.yml` 的 `package` job 和 preflight 里都跑（R3-04）；preflight 打包、检查，`npm-deploy` 只发布这些 tarball（R3-12）。
- [x] `engines.node` 统一为 `>=22.12.0`（R3-05）。
- [x] peer 范围放进具名 catalog `peers`，Renovate 只放宽、不抬下限（R3-06）。
- [x] `publish-npm.mjs` 在真实发布时拒绝不干净的工作区，以及不是 `v<version>` 那个提交的 HEAD（R3-07）。
- [x] `require` 条件有自己的 `.d.cts`；generator 的声明文件相对导入带扩展名；wow-react 只出 ESM 并有 `default` 条件（R3-09、R3-10、R3-11）。
- [x] 元数据与文案：generator 的 description 改成纯文本，三个包的 keywords 都带 `wow`，`homepage` 指向文档站各包的参考页（`https://wow.ahoo.me/reference/typescript/<包>/`），wow-react 有中文 README，wow-client 的 tarball 也带上两份 README；`repository.directory`、`bugs`、`license`、`author` 三个包一致（R3-25）。
- [x] 公开面逐名快照（D29）：wow-client、wow-react、wow-generator 都有 `test/surface/` 与 `test/publicSurface.test.ts`，构建时 `scripts/verify-package.mjs` 让产物与清单一致，不带 declaration map。
- [x] 版本范围（用户 2026-09-24 定）：兼容性页「版本范围」（中英文）是唯一写建议的地方——安装前设好波浪号的保存前缀（pnpm 写在项目的 `pnpm-workspace.yaml`：`savePrefix: '~'`；npm 写在项目的 `.npmrc`：`save-prefix=~`。pnpm 11 不再从 `.npmrc` 读 pnpm 的设置，rc.0 试用发现后改过），或者 `--save-exact`，因为次版本可以带破坏性改动；各包 README 与快速开始只用一句话链接过去，安装命令不写版本号。「支持期」一节指向 `SECURITY.md`。
- [x] fetcher peer 下限：fetcher 5.1.4 发到 npm 以后、发 9.2.0 之前，把 `pnpm-workspace.yaml` 里 `catalog:peers` 和默认 catalog 的 fetcher 下限抬到 `^5.1.5`（fetcher-react 同样），并让 `.github/scripts/package-check.mjs` 在 fetcher 自身声明的类型诊断（现在只作为 `upstream` 打印）上也失败。2026-09-24 完成：下限为 `^5.1.5 || ^6.0.0`；fetcher 的类型诊断现在让包检查失败，只放过 `ALLOWED_FETCHER_DIAGNOSTICS` 逐条列出的已知诊断，已知诊断不再出现时也失败。5.1.3 在 node16 下报 TS1479（CJS 声明 `require` 到 ESM 声明），5.1.4 通过。
- [x] fetcher 5.1.5（`Response` 全局扩展的 getter → readonly 属性）：把下限抬到 `^5.1.5`，删掉包检查里的放行。2026-09-24 完成：5.1.5 发到 npm 后，main 上的全量 CI 因放行过期而失败（包检查从 npm 装到 5.1.5），当天把下限抬到 `^5.1.5 || ^6.0.0`，`ALLOWED_FETCHER_DIAGNOSTICS` 清空。2026-09-25 按第二轮审查决定 5 去掉 `^6.0.0`，两个 catalog 都是 `^5.1.5`，见「peer 范围」。5.1.4 的 fetcher-eventstream 在 `responses.d.ts` 与 `responses.d.cts` 里都用 getter 扩展全局 `Response`，同一次编译里同时有 ESM 与 CJS 消费者时，`contentType`、`isEventStream` 报 TS2300。
- [x] 发布工程（P1）：发布说明模板 [RELEASE_NOTES_TEMPLATE.md](RELEASE_NOTES_TEMPLATE.md) 与 `.github/release.yml` 的分类（Breaking 在前、TypeScript 单列），标题带 `!` 的 PR 自动加 `breaking-change`；`npm-deploy` 之后的 `npm-smoke` 从 npm 装包冒烟（同一套 fetcher 诊断放行）；「出错时」的回滚手册。
- [x] 文档（R4）：兼容性矩阵、快速开始、错误处理、认证、SSR/Node、CI 重新生成、排障；包 README 写「随 Wow 9.2.0 发布」，站点与 README 的 TypeScript 样例由 `documentation/test/typescript-samples.test.mjs` 对构建产物做类型检查。
- [x] 视图引擎与视图存储进首发：两个包都在 `PUBLISHED`，`HELD_BACK` 为空（用户 2026-09-25、2026-10-01 定）。R2-82 做了形状与元数据：每个代码入口带 `default`、导出 `./package.json`、`homepage` 指参考页、keywords、`files` 不再带 `docs/design`、依赖一律外置（`verify-package.mjs` 15、16 守着）。第二轮审查 R2-42：包检查与 `npm-smoke` 覆盖它们——ESM import 引擎的五个入口与 wow-view-store、按导出路径解析四个样式表（`@import` 拿到的就是这些文件）、运行 `wow-view-engine theme-check --help`、在 node16/nodenext/bundler 下类型检查一个用到两个包的消费方；`package-check.test.mjs` 守着每个发布包的每个入口、样式表和 bin 都在冒烟里。两个包只有 ESM，不做 require。
- [x] Maven：`wow-view-store-api`、`-domain`、`-starter` 移出 `incubatingProjects`；`wow-bom` 从「发布的模块减去 BOM 自己」生成，不再约束示例模块、基准测试与未发布的模块（R2-43、R2-47）。
- [x] 兼容承诺（R2-46）：视图引擎与视图存储的 README 写「随 Wow 9.2.0 发布」与兼容规则（补丁版本不破坏公开面，次版本的破坏逐条写进发布说明，不加兼容层），安装照 wow-client 写；两个包的 AGENTS.md「Status」改成首发后的规则，保留存储数据迁移的例外。npm 页面上的 README 冻结在 tarball 里，所以这一条必须在 rc 之前。
- [x] 没标 `!` 的破坏也挡得住（R2-45）：PR 模板的 **Breaking** 框、正文不是「None.」的 `## Breaking` 一节、`BREAKING CHANGE:` 行都让 pr-labeler 加 `breaking-change`（`## Behaviour changes` 不加）；准入按标签拒绝补丁版本，并要求 `x.Y.0` 的发布说明点名每个破坏性 PR（见「发版准入」）。
- [x] 给 9.2.0 里没标 `!` 的破坏性 PR 补上标签，让准入也替它们把关（它们都写在草稿的「Breaking」里）：`for pr in 3429 3451 3461 3483 3485 3539 3570 3581 3595 3626 3791 3798 3823 3824 3825 3827 3832 3835; do gh pr edit "$pr" --repo Ahoo-Wang/Wow --add-label breaking-change; done`。2026-10-01 已运行（用户批准）。之后合并的、没标 `!` 的破坏性 PR 由 pr-labeler 或评审加标签。
- [x] 发布说明草稿 `release-notes/v9.2.0.md`（发布后按 F 第 1 步删掉，正文留在 [v9.2.0 的 release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.0)）：打 tag 之前按「发布说明」的命令补上草稿之后合并的 PR，填完占位符。2026-10-02 完成（到 #3866）：`v9.1.5` 以来的 54 个破坏性 PR 都在「Breaking」里点名；视图存储系统视图的网关规则写在 Highlights 下面的提示框里。2026-10-03 的 9.2.0 发版 PR 补到 #3870（rc 之后合并的 #3867、#3868、#3870 只改测试、控制台、文档与手册，不进说明；#3870 改了兼容性页的 pnpm 写法，说明里迁移步骤的 `save-prefix` 跟着改成 pnpm 的 `savePrefix` 与 npm 的 `save-prefix`），rc 的措辞改成正式版的，`missingFromNotes` 重跑仍是 54 个都点名。之后再合并的 PR，E.2 照同一套命令补上。
- [x] 依赖审计的一条发现（2026-10-01，见「日常发版」第 1 步）：wow-generator 经 ts-morph 带进 `brace-expansion` 5.0.9（两条 high、一条 moderate）。处置：锁文件更新到 5.0.12（`pnpm update -r brace-expansion`，minimatch 10.2.6 的范围 `^5.0.8` 本来就允许，没有 override），`pnpm audit --prod` 回到 0。
- [x] Docker 镜像（example、compensation、view store 服务端）不在预发布 tag 上构建推送：三个工作流的 tag 过滤排除 `v*.*.*-*`（`release-admission.test.mjs` 守着），rc 只发 npm（C.2）。
- [x] 发版 PR `chore(release): prepare 9.2.0-rc.0`：`pnpm set-version 9.2.0-rc.0`，更新 openapi 快照，`pnpm check:versions`。**rc 只改版本文件**（`gradle.properties`、各 `package.json`、openapi 快照，准入与测试要它们一致）；README 版本表与文档里给用户看的 Maven 版本（`existing-project.md`、`getting-started.md`）留在 9.1.5，因为 rc 不发 Maven Central，`wow-bom:9.2.0-rc.0` 不存在。它们在 9.2.0 的发版 PR 里按根 `AGENTS.md` 一起改。

### B. 仓库设置（维护者在 GitHub 上操作，一次）

1. environment `npm-publish`：只允许 `v*` tag 部署，不设审批人（2026-10-04 用户决定；之前要维护者审批）。npm 的 Trusted Publisher 绑定这个 environment，所以 job 仍在它里面跑；先后顺序由工作流保证：`npm-deploy` 依赖 Maven 两路成功。

   ```bash
   gh api -X PUT repos/Ahoo-Wang/Wow/environments/npm-publish --input - <<'JSON'
   {
     "wait_timer": 0,
     "reviewers": [],
     "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
   }
   JSON
   gh api -X POST repos/Ahoo-Wang/Wow/environments/npm-publish/deployment-branch-policies \
     -f name='v*' -f type=tag
   ```

   网页操作等价：Settings → Environments → New environment `npm-publish` → Required reviewers 不勾选 → Deployment branches and tags 选 Selected branches and tags → Add deployment branch or tag rule → Ref type 选 Tag，名称 `v*`。

2. 默认工作流权限改为只读。所有工作流都已经声明了自己需要的权限。

   ```bash
   gh api -X PUT repos/Ahoo-Wang/Wow/actions/permissions/workflow \
     -f default_workflow_permissions=read -F can_approve_pull_request_reviews=false
   ```

3. `v*` tag 规则：只有管理员能创建，任何人都不能移动或删除（管理员可以绕过）。

   ```bash
   gh api -X POST repos/Ahoo-Wang/Wow/rulesets --input - <<'JSON'
   {
     "name": "Release tags",
     "target": "tag",
     "enforcement": "active",
     "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
     "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }],
     "rules": [{ "type": "creation" }, { "type": "update" }, { "type": "deletion" }]
   }
   JSON
   ```

4. 三个 gate 设为 main 的必需检查（ruleset 16907411）。`PUT` 会整体替换 ruleset，下面的内容保留了现有的全部规则，只在 `required_status_checks` 里加了三项。`integration_id` 15368 是 GitHub Actions。三条流水线对每个指向 main 的 PR 都会触发，gate 一定会上报。设之前先修掉 view-engine 在 Node 22 分片上的偶发失败（R3-21），否则会反复卡住合并。

   ```bash
   gh api -X PUT repos/Ahoo-Wang/Wow/rulesets/16907411 --input - <<'JSON'
   {
     "name": "Copilot review for default branch",
     "target": "branch",
     "enforcement": "active",
     "conditions": { "ref_name": { "exclude": [], "include": ["~DEFAULT_BRANCH"] } },
     "bypass_actors": [],
     "rules": [
       { "type": "deletion" },
       { "type": "non_fast_forward" },
       { "type": "copilot_code_review", "parameters": { "review_on_push": true, "review_draft_pull_requests": false } },
       {
         "type": "pull_request",
         "parameters": {
           "required_approving_review_count": 0,
           "dismiss_stale_reviews_on_push": false,
           "required_reviewers": [],
           "require_code_owner_review": false,
           "require_last_push_approval": false,
           "required_review_thread_resolution": false,
           "require_extra_approval_for_unattributed_changes": true,
           "allowed_merge_methods": ["squash", "rebase"]
         }
       },
       {
         "type": "required_status_checks",
         "parameters": {
           "strict_required_status_checks_policy": false,
           "do_not_enforce_on_create": false,
           "required_status_checks": [
             { "context": "PR Safety", "integration_id": 15368 },
             { "context": "typescript-gate", "integration_id": 15368 },
             { "context": "typescript-contract-gate", "integration_id": 15368 },
             { "context": "typescript-storybook-gate", "integration_id": 15368 }
           ]
         }
       }
     ]
   }
   JSON
   ```

5. 以后（可选）：全仓 workflow 都按 SHA 锁定以后，打开 `sha_pinning_required`。现在只有发布、文档、部署几条工作流和所有 `actions/checkout` 锁定了，打开会让其余工作流失败。

### C. 手工发布 `9.2.0-rc.0`（维护者本机，只做一次）

**已完成**（2026-10-03）：维护者在本机从 tag `v9.2.0-rc.0`（`d7ace5e46`）手工发布，五个发布包都有 `9.2.0-rc.0`。

这次首发的教训：

- 发布在真正的终端里跑：每个包的 OTP 是交互式提示。agent 会话里的 `!` 命令和 Run 按钮在 agent 的目录里执行，不在临时 clone 里。
- `~/.npm` 里有 root 的文件（`EACCES`）时，不用 sudo，换一个一次性缓存：`export npm_config_cache=$(mktemp -d)`。
- 新包刚发出的几分钟里，`npm view` 可能答 404，registry 还在传播；等一会儿再核对。

1. 确认 npm 账号是组织 `ahoo-wang` 的成员、有发布权限、开了 2FA；本机 `npm -v` 不低于 11.5.1，`node -v` 不低于 22.12.0。
2. A 里的发版 PR 合并以后，在它的合并提交上打 tag 并推送。**不创建 GitHub release**：release 会触发 Maven 发布，rc 只发 npm。所以 rc 的发版 PR 只改版本文件，README 与文档里的 Maven 版本仍指最近一个正式版，到 9.2.0（E）的发版 PR 才改。三个镜像工作流（`example-deploy.yml`、`compensation-deploy.yml`、`view-store-deploy.yml`）在 `v*.*.*` tag 上推镜像，但排除了带 `-` 的预发布 tag，所以推 rc 的 tag 不会推镜像，也不会移动 `X.Y`、`latest`。

   ```bash
   git fetch origin
   git tag v9.2.0-rc.0 <发版 PR 的合并提交>
   git push origin v9.2.0-rc.0
   ```

3. 在 tag 上手动触发三条流水线，全部等到绿（dispatch 时 scope 全开）：

   ```bash
   for workflow in typescript.yml typescript-contract.yml typescript-storybook.yml; do
     gh workflow run "$workflow" --repo Ahoo-Wang/Wow --ref v9.2.0-rc.0
   done
   gh run list --repo Ahoo-Wang/Wow --event workflow_dispatch --limit 3
   ```

   在 `typescript-contract.yml` 的这次运行里确认 `contract-elasticsearch` 确实跑了并且通过（它在 `typescript-contract-gate` 里）：视图引擎和 WowViewStore 对着事件与快照都在 Elasticsearch 9.2.6 上的示例服务端，只有这个 job 检查。补偿控制台的试用（C′）只走 MongoDB。

4. 在临时目录做全新 clone 并发布。脚本在真实发布时会拒绝不干净的工作区和不是 tag 提交的 HEAD；预发布版本自动打 dist-tag `next`。

   ```bash
   git clone https://github.com/Ahoo-Wang/Wow.git wow-release && cd wow-release
   git checkout --detach v9.2.0-rc.0
   git status --porcelain                                 # 必须为空
   pnpm install --frozen-lockfile && pnpm build:typescript
   git status --porcelain                                 # 仍然为空
   node .github/scripts/package-check.mjs                 # 与 CI 同一套包检查
   node .github/scripts/publish-npm.mjs --dry-run         # 看清文件、版本和 dist-tag next
   npm login
   node .github/scripts/publish-npm.mjs --no-provenance   # 每个包要一次 OTP
   ```

5. 核对每个发布包，然后删掉临时 clone：

   ```bash
   PACKAGES=$(node .github/scripts/publish-npm.mjs --list)        # 在 wow-release 里运行
   for pkg in $PACKAGES; do npm view "$pkg@9.2.0-rc.0" version; npm dist-tag ls "$pkg"; done
   # 每个包都有 9.2.0-rc.0，dist-tag 是 latest: 9.2.0-rc.0 和 next: 9.2.0-rc.0
   mkdir ../rc-check && cd ../rc-check && npm init -y >/dev/null
   npm i $(for pkg in $PACKAGES; do printf '%s@next ' "$pkg"; done) react react-dom react-router mingo
   npx wow-generator --version                                    # 9.2.0-rc.0
   npx wow-view-engine theme-check --help | head -1               # Usage: wow-view-engine theme-check …
   node --input-type=module -e "await import('@ahoo-wang/wow-view-engine/ui'); await import('@ahoo-wang/wow-view-store'); console.log('ok')"
   ```

   `latest` 也指向 rc，不是脚本的错：npm 给一个全新的包的第一个版本总是打上 `latest`，不管发布时指定的 dist-tag。所以在 9.2.0 之前，不带版本的 `npm i <包>` 装到的是 rc。CI 发 9.2.0 时 `latest` 移到 9.2.0（E 第 4 步），在那之前不手动改它。

### C′. 在补偿控制台上试用 rc（发 `latest` 的前置条件）

**已完成**（2026-10-03）：在 `9.2.0-rc.0` 上通过，结果记在 [MIGRATION.md](MIGRATION.md)「进度」的「C′ 试用记录」。发现的问题都在发布包之外，#3870 在 main 上修好，按第 7 步只在 rc.0 上重跑了受影响的步骤，不发 rc.1。

`9.2.0-rc.0` 发到 npm 以后，把补偿控制台（`compensation/dashboard`）依赖的发布包从工作区换成 npm 上的 rc，端到端跑通：生成、类型检查、lint、构建、单元测试、浏览器测试，再对着真实的补偿服务端走查一遍；然后在一个空目录里用 `@next` 逐字照快速开始做一遍（第 6 步）。**任何一步退出码不为 0，或者走查发现问题，都不发 9.2.0。** 控制台同时用全部五个发布包（wow-client、wow-react、wow-generator、wow-view-engine、wow-view-store），是它们在仓库里唯一的真实应用（integration-test 与 storybook 只是测试）（用户 2026-09-25 定：「不需要在 wow-project-template 中使用，补偿控制台就是真实案例」；[MIGRATION.md](MIGRATION.md) 第 4a 步判据③）。

试用在一个**永不合并**的临时分支上做。main 上的控制台始终用 `workspace:*`，9.2.0 发布后删掉这个分支。

视图引擎与视图存储对 wow-client（视图存储还对视图引擎）的 peer 与 devDependency 都是 `workspace:~`；只换控制台自己的依赖，就会从工作区另解析出一份，与控制台的 rc 成了两份，试用就不干净。所以用根目录的 pnpm `overrides` 把整个工作区的五个发布包一律指向 npm 上的 rc，**一个工作区包也不构建**。

1. 分支。在一个新的 clone 或 worktree 里从 rc 的 tag 开分支，**不要运行 `pnpm build:typescript`**，也不要构建任何 `typescript/*` 包：它们的 `dist` 不存在，万一依赖仍然指向工作区，后面的构建会直接失败，而不是悄悄用上本地代码。

   ```bash
   git fetch origin --tags
   git worktree add ../wow-rc-trial -b chore/compensation-9.2.0-rc.0 v9.2.0-rc.0
   cd ../wow-rc-trial
   ```

2. 换成 npm 上的 rc。在根目录的 `pnpm-workspace.yaml` 末尾加上 `overrides`（这个工作区的 pnpm 设置都写在这里，根 `package.json` 没有 `pnpm` 字段），每个发布包一行（`node .github/scripts/publish-npm.mjs --list`）。它会把每个工作区成员里这些包的说明符，包括控制台的 `workspace:*` 和视图引擎、视图存储的 `workspace:~`，都换成精确的 rc，所以控制台的 `package.json` 不用改：

   ```yaml
   overrides:
     '@ahoo-wang/wow-client': 9.2.0-rc.0
     '@ahoo-wang/wow-react': 9.2.0-rc.0
     '@ahoo-wang/wow-generator': 9.2.0-rc.0
     '@ahoo-wang/wow-view-engine': 9.2.0-rc.0
     '@ahoo-wang/wow-view-store': 9.2.0-rc.0
   ```

   ```bash
   pnpm install --no-frozen-lockfile                       # overrides 改了，锁文件要跟着更新
   ```

   tag 上工作区包的版本也是 `9.2.0-rc.0`，所以要确认 pnpm 真的从 registry 取包。pnpm 10 的 `link-workspace-packages` 默认是 `false`，仓库没有 `.npmrc`，`pnpm-workspace.yaml` 也没有改它，所以 override 里不带 `workspace:` 的版本号一律按 registry 解析（2026-09-25 核对：rc 发布之前，把 override 写成与工作区同版本的 `9.1.5`，`pnpm install` 直接报 `ERR_PNPM_FETCH_404 GET https://registry.npmjs.org/@ahoo-wang%2Fwow-react`，没有去链接 `typescript/*`；再把 override 指向 `pnpm pack` 出来的三个 tarball，下面四项核对全部符合，视图引擎和控制台都构建通过）。装完核对：

   ```bash
   pnpm list -r --depth 0 $(node .github/scripts/publish-npm.mjs --list)
   # 每一行都应是 …@9.2.0-rc.0；出现 …@link:../../typescript/… 就是还在用工作区
   pnpm why -r @ahoo-wang/wow-client | tail -1
   # Found 1 version of @ahoo-wang/wow-client（只按工作区链接解析时这条命令没有输出）
   realpath compensation/dashboard/node_modules/@ahoo-wang/wow-client \
     typescript/wow-view-engine/node_modules/@ahoo-wang/wow-client
   # 两行完全相同，都落在 node_modules/.pnpm/@ahoo-wang+wow-client@9.2.0-rc.0…/ 下，而不是 typescript/wow-client
   ls -d typescript/*/dist                                  # 没有任何一个
   pnpm --dir compensation/dashboard exec wow-generator --version   # 9.2.0-rc.0
   ```

3. 启动补偿服务端。控制台的查询需要 MongoDB（服务端没有内存查询后端），所以这里在[补偿参考案例](../documentation/docs/zh/reference/example/compensation.md#本地服务启动、健康与路由验证)的本地命令上换成 MongoDB 存储，只绑定 loopback，调度、Kafka、Redis 仍然关闭。在仓库根目录另开一个终端：

   ```bash
   docker run -d --name wow-rc-mongo -p 27118:27017 \
     -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root mongo:7.0
   ./gradlew :wow-compensation-server:installDist

   SERVER_PORT=18083 \
   SERVER_ADDRESS=127.0.0.1 \
   SPRING_AUTOCONFIGURE_EXCLUDE='org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration' \
   SPRING_MONGODB_URI='mongodb://root:root@127.0.0.1:27118/compensation_db?authSource=admin' \
   COSID_MACHINE_DISTRIBUTOR_TYPE=manual \
   COSID_MACHINE_DISTRIBUTOR_MANUAL_MACHINE_ID=1 \
   WOW_COMPENSATION_SCHEDULER_ENABLED=false \
   WOW_COMPENSATION_WEBHOOK_WEIXIN_URL=false \
   WOW_KAFKA_ENABLED=false \
   WOW_COMMAND_BUS_TYPE=in_memory \
   WOW_EVENT_BUS_TYPE=in_memory \
   WOW_EVENTSOURCING_STATE_BUS_TYPE=in_memory \
   WOW_EVENTSOURCING_STORE_STORAGE=mongo \
   WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
   WOW_PREPARE_ENABLED=false \
   WOW_REDIS_ENABLED=false \
   WOW_ELASTICSEARCH_ENABLED=false \
   java \
     -Dspring.config.location=file:compensation/wow-compensation-server/src/main/resources/application.yaml \
     -cp 'compensation/wow-compensation-server/build/install/wow-compensation-server/lib/*' \
     me.ahoo.wow.compensation.server.CompensationServerKt
   ```

   `curl -fsS http://127.0.0.1:18083/actuator/health/liveness` 返回 `{"status":"UP"}` 即可继续。（2026-09-25 在 main 上核对过：服务端启动、健康检查和下面这条写入命令都通过。MongoDB 8.x 在 Linux 内核 6.19～7.0.13 上拒绝启动（SERVER-121912），Docker Desktop 可能正是这种内核，所以这里用 `mongo:7.0`；要用 8.x，就像 CI 的服务那样加 `-e GLIBC_TUNABLES=glibc.pthread.rseq=1`。）再写入一条失败记录，让列表和聚合有数据可看：

   ```bash
   curl -fsS -X POST http://127.0.0.1:18083/execution_failed/create_execution_failed \
     -H 'Content-Type: application/json' -H 'Command-Wait-Stage: SNAPSHOT' \
     -d "{\"eventId\":{\"id\":\"rc-event-1\",\"version\":1,\"aggregateId\":{\"contextName\":\"rc-trial\",\"aggregateName\":\"order\",\"aggregateId\":\"order-1\",\"tenantId\":\"(0)\"}},\"function\":{\"contextName\":\"rc-trial\",\"processorName\":\"OrderSaga\",\"name\":\"onOrderCreated\",\"functionKind\":\"EVENT\"},\"error\":{\"errorCode\":\"RC_TRIAL\",\"errorMsg\":\"rc trial\",\"stackTrace\":\"\",\"bindingErrors\":[]},\"executeAt\":$(($(date +%s) * 1000)),\"recoverable\":\"RECOVERABLE\"}"
   # 返回 "succeeded":true
   ```

4. 生成、检查、构建、测试，逐条看退出码，全部为 0：

   ```bash
   pnpm --dir compensation/dashboard exec wow-generator generate \
     -i http://127.0.0.1:18083/v3/api-docs -o src/generated
   git diff --stat -- compensation/dashboard/src/generated
   pnpm --dir compensation/dashboard exec tsc -b
   pnpm --dir compensation/dashboard lint
   pnpm --dir compensation/dashboard build
   pnpm --dir compensation/dashboard coverage
   pnpm --dir compensation/dashboard exec playwright install chromium
   pnpm --dir compensation/dashboard test:browser
   ```

   - `generate` 不用 `package.json` 里的 `generate` 脚本，那个脚本读的是开发集群的地址。`git diff` 应当没有差异。有差异时，在 main 上用工作区的生成器（先 `pnpm --filter @ahoo-wang/wow-generator build`，再 `pnpm install`，见[控制台 README](../compensation/dashboard/README.md#生成客户端边界)第 2 步）对同一个服务端再生成一次：工作区也有同样的差异，说明提交的产物过期了，在 main 上重新生成提交；只有 npm 上的生成器才有的差异，就是打包问题。
   - 不用 `pnpm --filter wow-compensation-dashboard... build`：这个过滤器会连带构建控制台的工作区依赖，而试用一个工作区包也不构建，这里直接构建控制台自己即可。`coverage` 与 `test:browser` 同 `dashboard-test.yml`。
   - `test:browser` 在 `127.0.0.1:4174` 起构建好的 preview，接口由用例里的 `page.route` 桩住，不连服务端；它验证的是 rc 包在真实浏览器里的渲染和交互。

5. 对着真实服务端走查。服务端的 `spring.web.resources.static-locations` 是 `file:./compensation/dashboard/dist/`，从仓库根目录启动时直接提供上一步构建的控制台，生产构建的 `VITE_API_BASE_URL` 是 `/`，请求就落在同一个服务端上。先跑自动冒烟，退出码为 0：

   ```bash
   WOW_COMPENSATION_URL=http://127.0.0.1:18083 pnpm --dir compensation/dashboard test:browser
   ```

   设了 `WOW_COMPENSATION_URL` 时 Playwright 只跑 `e2e/real-server/`、不起 preview：它自己写入两条失败执行，直接打开「失败执行」`/executions`，断言真实的行渲染出来、按处理器加一个条件后只剩那一行；再打开「待重试」与「已到重试时间」，断言服务端按自己的时钟接受 `BEFORE_NOW`／`AFTER_NOW`（新写入的两条在前者、不在后者）；全程没有 4xx、5xx 与页面错误。它补上第 4 步 `test:browser` 打桩测不到的那一半——rc 包对真实服务端的查询。然后人工走查：打开 `http://127.0.0.1:18083/`，首页两类聚合都有数字，`/active`（跳到「失败执行」的「活动中」）列表里有第 3 步写入的记录，打开详情，看「尝试记录」，展开「全部事件（n）」，浏览器控制台没有错误，网络面板没有 4xx、5xx。

   视图存储一并走查：补偿服务端内嵌 `wow-view-store-starter`（从 rc 的 tag 构建），控制台经 npm 上 rc 的 `@ahoo-wang/wow-view-store` 读写。补偿控制台没有登录身份，另存时「仅自己」不可选（没有创建权限），只能建共享视图；自动冒烟的最后一条也是这样存的。在「失败执行」里把当前视图另存为一个共享视图、改名，刷新页面后它仍在、名字是改过的、受众是共享；网络面板里 `/view-store/…` 的请求没有 4xx、5xx。

6. 照快速开始走一遍，用 npm 上的 rc。控制台用的是仓库里的写法；这一步用的是文档写给新用户的写法，两者都通过才算数（F.2 发布后会用 `latest` 再走一遍，那时出了问题只能发补丁）。

   1. 服务端：示例服务端，从 rc 的 tag 构建，存储用 MongoDB。它的 Wow 版本就是 rc，所以不带 `limit` 的列表查询用的是服务端默认列表大小；记下这一点，兼容性页「不带 limit 的列表查询」写的是各版本的差别。在第 1 步的 worktree 里另开一个终端：

      ```bash
      docker run -d --name wow-rc-example-mongo -p 27119:27017 \
        -e GLIBC_TUNABLES=glibc.pthread.rseq=1 \
        -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root mongo:8.3.11
      ./gradlew :example-server:installDist
      cd example/example-server/build/install/example-server && mkdir -p logs data
      SERVER_PORT=8080 \
      SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration \
      SPRING_MONGODB_URI='mongodb://root:root@localhost:27119/wow_example_db?authSource=admin' \
      WOW_EVENTSOURCING_STORE_STORAGE=mongo WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
      bin/example-server
      ```

   2. 在仓库外的空目录里，逐字照[快速开始](../documentation/docs/zh/guide/typescript/quick-start.md)第 2～5 步操作，唯一的改动是给两个 Wow 包加上 `@next`：`npm init -y && npm pkg set type=module`，先按「版本范围」的 pnpm 写法在 `pnpm-workspace.yaml` 里写 `savePrefix: '~'`（`pnpm config get save-prefix` 应答 `~`），然后是页面上的两条 `pnpm add`（`@ahoo-wang/wow-client@next`、`@ahoo-wang/wow-generator@next`，其余照抄），页面上的 `tsconfig.json`，第 3 步的生成命令，第 4、5 步的 `src/cart.ts`、`src/main.ts`。装完 `package.json` 里两个 Wow 包的范围都以 `~` 开头。rc.0 的试用照旧页面写 `.npmrc`，pnpm 11 不读它（`pnpm config get save-prefix` 答 `undefined`），存成了 `^`；pnpm 10 两处都读。
   3. `pnpm exec tsc -p tsconfig.json` 与 `node dist/main.js` 退出码都为 0，输出的三行与页面一致（`SNAPSHOT: cart … v1`、`items: [ { productId: 'book-1', quantity: 2 } ]`、`carts holding book-1: 1`）；`pnpm exec wow-generator --version` 是 rc；`node_modules/@ahoo-wang/fetcher-openapi` 不存在（生成器不再以它为 peer，第二轮审查 P1-11）。页面上的 `typescript` 装到的是最新的 7.x；再 `pnpm add -D typescript@~6.0.0` 把 `tsc` 重跑一遍，退出码也为 0，下限 6.0 同样照页面走通（P1-15）。页面上有一处照做不通，就是文档缺陷，与代码缺陷一样挡发布。
   4. wow-react：控制台已经在第 4、5 步用 rc 的 wow-react 跑过单元测试、浏览器测试和真实服务端走查；第 5 步走查时，列表、分页和详情页的请求都经 wow-react 的 Hook 发出，确认它们在网络面板里各只发一次、切换筛选时旧请求被取消（状态为 canceled）。
   5. 视图存储的端口一致性：按 [integration-test 的 README](integration-test/README.md#wowviewstore-against-the-view-store-server) 从 rc 的 tag 构建并启动独立的视图存储服务端（`./gradlew :wow-view-store-server:installDist`，用上面示例服务端的 MongoDB，换一个库名），示例服务端重启时带上同样四个 `--wow.view-store.system-views[0].…` 参数，然后 `pnpm --dir typescript/integration-test exec vitest run test/view-store`，退出码为 0。第 2 步的 overrides 让 integration-test 用的也是 npm 上的 rc。

7. 记录与重来。把结果记进 [MIGRATION.md](MIGRATION.md)「进度」，写明两台服务端的 Wow 版本（补偿服务端、示例服务端都从 rc 的 tag 构建）。发现问题就在 Wow 的 main 上修复，然后看问题落在哪里（用户 2026-10-03 定）：
   - 落在发布包的内容里（五个 npm 包打进 tarball 的文件）：发下一个 rc（如 `9.2.0-rc.1`），从新 tag 开新分支，从第 1 步重来。
   - 落在发布包的内容之外（测试、控制台自己的代码、文档、这份手册）：不发新 rc。修复合并到 main 以后，只在同一个 rc 上重跑受影响的步骤。rc.0 的试用就是这样：真实服务端冒烟的两条用例、控制台提交的生成客户端、兼容性页的 pnpm 写法与这份手册在 main 上修好（#3870），重跑第 4～6 步里失败的几项，仍用 rc.0 的包。

   分支可以推到远端留证，但**不开 PR、不合并**；9.2.0 发布以后删掉它（`git push origin --delete chore/compensation-9.2.0-rc.0`），停掉两个服务端，`docker rm -f wow-rc-mongo wow-rc-example-mongo`。

### D. 配置 Trusted Publisher（每个发布包各一次）

**已完成**（2026-10-03，用户确认）：五个发布包都配好了 Trusted Publisher。

对 `node .github/scripts/publish-npm.mjs --list` 列出的每个包（9.2.0：wow-client、wow-react、wow-generator、wow-view-engine、wow-view-store）各做一遍；漏掉一个，CI 的 `npm-deploy` 就会在它那里因 OIDC 失败（前面的包已经发出，重跑会跳过它们，补上配置后重跑即可）。以后 `PUBLISHED` 再加包，同样先手工发一个 rc 让包名存在，再配这里。

1. npmjs.com → 包 → Settings → Trusted Publisher → GitHub Actions：
   - Organization or user：`Ahoo-Wang`
   - Repository：`Wow`
   - Workflow filename：`package-deploy.yml`
   - Environment name：`npm-publish`
2. 同一页 Publishing access 选 **Require two-factor authentication and disallow tokens**。之后只能通过 OIDC，或者本人加 2FA 发布。
3. 核对：五个包的 Settings 页都显示同一个 Trusted Publisher（`Ahoo-Wang/Wow`、`package-deploy.yml`、`npm-publish`），Publishing access 都是禁止 token。

### E. CI 发布 `9.2.0`

**已完成**（2026-10-03）：GitHub release `v9.2.0`（`7fe4934b5`）触发的 [Packages Deploy](https://github.com/Ahoo-Wang/Wow/actions/runs/37092332830) 每个 job 都成功——`admission`（三条完整运行与破坏性 PR 点名）、`preflight`、`github-deploy`（GitHub Packages）、`central-deploy`（Maven Central，包括 `wow-view-store-api`、`-domain`、`-starter`）、`npm-deploy`（五个发布包都带 provenance，dist-tag 是 `latest: 9.2.0`、`next: 9.2.0-rc.0`）、`npm-smoke`（Node 22.12.0 与 24 都绿）。三个镜像的 `9.2.0`、`9.2`、`latest` 已推到 Docker Hub、ghcr、阿里云，同一个镜像的三个 tag 在三个仓库里 digest 一致。第 5 步同日完成：重跑的 `npm-deploy`（同一运行的第 2 次尝试，经维护者审批）对五个包都输出「already on npm; skipped」，`npm-smoke` 两个 job 仍然全绿。

1. 前提：C′（补偿控制台试用）在最后一个 rc 上通过，A 里的 fetcher peer 下限已处理。发版 PR `chore(release): prepare 9.2.0`：`pnpm set-version 9.2.0`，按根 `AGENTS.md` 更新 README 版本表、`existing-project.md`、`getting-started.md`（中英文）与 openapi 快照，合并。
2. GitHub → Releases → Draft a new release：tag `v9.2.0`（在 main 上新建），正文就是草稿 `release-notes/v9.2.0.md`（去掉开头的注释；发布后按 F 第 1 步删掉，正文留在 release 上）。**在这个页面上直接 Publish release，不要先 Save draft**：`package-deploy.yml` 由 release 的 `created` 事件触发，先存成草稿、以后再发布的 release 只发出 `published`，不会有 `created`，发布流水线就不运行。已经存成草稿再发布的，在 tag 上手动运行 Packages Deploy（「发布流水线」第一段）。`v9.1.5..HEAD` 里有约 1370 个从 fetcher 导入的提交，不能直接用自动生成的说明（R3-29）；`--first-parent` 只剩 main 上的几百个合并提交。草稿已经写好的几块，发之前再核对一遍：
   - **Breaking / TypeScript**：三个客户端包在 npm 上是首发，它们的 `!` 提交改的是还没发布的代码，所以这一节写的是**相对 `@ahoo-wang/fetcher-wow`、`fetcher-generator`、`fetcher-react` 的迁移**（包名、入口、Condition 查询移到 `/legacy`、生成器 CLI 与文件名等），逐条给出步骤，详细内容链接[迁移指南](../documentation/docs/zh/guide/typescript/migration.md)。
   - **Breaking / Kotlin / JVM**：查询子系统的 SPI 与类型、9.1 编译的自定义 `QueryGateway`（`describe` 抛 `QuerySchemaUnavailableException`）、改名的 bean、9.1 的 schema 声明文件、新的启动检查（R2-45 的清单，#3827 的「For B7」一节）；开头一段写明 9.1 与 9.2 混跑时已知的差别。
   - **Breaking / Server behaviour**：`spaced`（#3791，批准的 v9 例外：升级前给要隔离的聚合声明 `spaced = true`；进程内的来源与 saga 也不再带 space；9.1 与 9.2 混跑时同一个查询可能随节点返回不同的行）、`/schema` 响应体（D77，弃用的 refresh 别名，混跑时退回宿主的定义）、游标 token、Elasticsearch（启动时创建随包的索引定义与所需权限、整数映射字段上的小数、存在性过滤读 `_ignored`）。
   - **Behaviour changes**（不破坏兼容的可见变化）：Elasticsearch（缺失的索引答空而不是 503 #3823、首写前的 2 秒窗口、已有索引的漂移告警、只对新索引生效的模板规则与混跑时模板谁说了算、`ignore_above` 8191 可查询、结果窗口）、查询错误、视图存储的输入限制。
   - **Pre-release changes**：只改了视图引擎未发布代码的 `!` PR 列在一行里。

   发布前在本机跑一遍准入同款的核对，确认草稿点名了每个破坏性 PR（准入在 release 上做同样的检查，漏一个就拒绝）：

   ```bash
   LABELLED=$(mktemp)
   gh api --method GET search/issues -f q="repo:Ahoo-Wang/Wow is:pr is:merged label:breaking-change merged:>=$(git log -1 --format=%cs v9.1.5)" \
     -f per_page=100 --paginate --slurp > "$LABELLED"
   LABELLED="$LABELLED" node --input-type=module -e '
     import { execFileSync } from "node:child_process";
     import { readFileSync } from "node:fs";
     import { isBreakingCommit, missingFromNotes } from "./.github/scripts/release-admission.mjs";
     const labelled = new Set(JSON.parse(readFileSync(process.env.LABELLED, "utf8")).flatMap(page => page.items).map(item => item.number));
     const commits = execFileSync("git", ["log", "--first-parent", "--format=%H%x00%B%x1e", "v9.1.5..HEAD"], { encoding: "utf8", maxBuffer: 1e8 })
       .split("\x1e").map(record => record.replace(/^\n/, "")).filter(Boolean)
       .map(record => { const [sha, message] = record.split("\0"); return { sha, message: message.trim() }; });
     const missing = missingFromNotes(commits.filter(commit => isBreakingCommit(commit, labelled)), readFileSync("typescript/release-notes/v9.2.0.md", "utf8"));
     console.log(missing.map(commit => commit.message.split("\n")[0]).join("\n") || "every breaking pull request is named");'
   ```

   发布 release。

3. `admission` 触发三条完整运行并等待，`preflight` 跑完后 `github-deploy`、`central-deploy` 直接开始，两路都成功后 `npm-deploy` 自动开始，不需要审批；任一路失败则 `npm-deploy` 不运行。
4. 核对：`npm-smoke` 两个 job 都绿；npmjs.com 上每个发布包的 9.2.0 都显示 Provenance 徽章；每个包的 dist-tag 都是 `latest: 9.2.0`、`next: 9.2.0-rc.0`（`for pkg in $(node .github/scripts/publish-npm.mjs --list); do npm dist-tag ls "$pkg"; done`）。Maven Central 上 `wow-view-store-api`、`-domain`、`-starter` 的 9.2.0 可以解析，`wow-bom` 9.2.0 的约束里有它们、没有示例模块。Docker 镜像：三个镜像工作流在 tag 创建时就运行，**不经过准入和 preflight**，所以单独核对——Actions 里 Example／Compensation／View Store Docker Image Deploy 在 `v9.2.0` 上各有一次成功的运行，三个仓库（Docker Hub `ahoowang/`、`ghcr.io/ahoo-wang/`、阿里云 `registry.cn-shanghai.aliyuncs.com/ahoo/`）里每个镜像的 `9.2.0`、`9.2`、`latest` 指向同一个 digest：

   ```bash
   for image in wow-example-server wow-compensation-server wow-view-store-server; do
     for tag in 9.2.0 9.2 latest; do
       printf '%s:%s ' "$image" "$tag"; docker buildx imagetools inspect "ahoowang/$image:$tag" --format '{{.Manifest.Digest}}'
     done
   done
   # 每个镜像的三行 digest 相同；ghcr.io/ahoo-wang/ 与 registry.cn-shanghai.aliyuncs.com/ahoo/ 同样核对
   ```

5. 在运行页面重跑 `npm-deploy`（Re-run failed jobs 或 Re-run job），每个包都应输出「already on npm; skipped」，验证幂等。重跑不需要审批。

### F. 发布以后

**第 1、2 步已完成**（2026-10-03）：首发记在 MIGRATION「进度」的「首发记录」，草稿已删，文档翻成已发布；照快速开始从 npm 安装、生成、编译、运行都通过（结果与 pnpm 11 `minimumReleaseAge` 的一处观察也在「首发记录」里）。第 3 步还没做。

1. 在 [MIGRATION.md](MIGRATION.md)「进度」里记下首发；删掉草稿 `release-notes/v9.2.0.md`（GitHub release 就是记录）。
2. 翻转文档状态：`documentation/docs/{en,zh}/guide/typescript/` 的 `index.md`、`compatibility.md`「发布状态」、`quick-start.md` 的提示框和 `troubleshooting.md` 的 `E404` 一行，视图引擎与视图存储各页顶部「随 Wow 9.2.0 发布」提示框里的「在此之前不在 npm 上／it is not on npm before that」，以及 `reference/typescript/index.md`，把「尚未上 npm／not yet on npm」改成已发布；包 README 已冻结在 tarball 里，只写了「随 Wow 9.2.0 发布」与兼容规则，不用改。然后在一个空目录里照[快速开始](../documentation/docs/zh/guide/typescript/quick-start.md)从 npm 安装、生成、编译一遍，确认页面上的安装命令能用。
3. 删掉 C′ 的临时分支 `chore/compensation-9.2.0-rc.0`（不合并，main 上的控制台保持 `workspace:*`）。按 MIGRATION「下一步」，再对 `fetcher-wow`、`fetcher-generator` 执行 `npm deprecate`（对外操作，先问用户）。
4. 每次发布、`ahoowang/wow-example-server:<版本>` 镜像推送以后，把 `.github/workflows/mixed-version.yml` 的 `PREVIOUS_IMAGE` 改成最新发布的镜像：混部测试守的是上一个发布与当前构建之间的边界（9.3.0 发布后是 `9.3.0`，#4027）。

## 日常发版

1. 依赖审计。每次发版前在发版提交上运行，首发的 `9.2.0-rc.0`、`9.2.0` 也一样：

   ```bash
   pnpm install --frozen-lockfile
   pnpm audit --prod    # 工作区的运行时依赖，包括每个发布包的 dependencies
   pnpm audit --json | node --input-type=module -e '
     import { execFileSync } from "node:child_process";
     import { readFileSync } from "node:fs";
     import { PUBLISHED } from "./.github/scripts/publish-npm.mjs";
     // The JSON gives one sample path per finding, so ask pnpm which published
     // package reaches each vulnerable version through what it ships.
     const { advisories } = JSON.parse(readFileSync(0, "utf8"));
     const hits = Object.values(advisories).flatMap(advisory => {
       const versions = new Set(advisory.findings.map(finding => finding.version));
       return PUBLISHED.filter(dir =>
         JSON.parse(execFileSync("pnpm", ["why", "--prod", "--filter", `./${dir}`, advisory.module_name, "--json"], { encoding: "utf8", maxBuffer: 1e8 }) || "[]")
           .some(found => versions.has(found.version)),
       ).map(dir => `${dir}: ${advisory.module_name} ${[...versions].join(", ")} (${advisory.severity}, ${advisory.url})`);
     });
     console.log(hits.join("\n") || "published packages: no advisories");
     process.exitCode = hits.length ? 1 : 0;'
   ```

   第一条查整个工作区的运行时依赖；第二条把每条发现归到发布包（`PUBLISHED`）上，只看它们装进用户项目的依赖（`dependencies` 及其传递依赖；视图引擎的 echarts、Base UI、dnd-kit 等也在内）。`pnpm audit --json` 每条发现只给一条示例路径，按路径前缀过滤会漏（9.2.0 之前的写法就漏了 wow-generator 的一条），所以按包名与版本问 `pnpm why`。peer（fetcher、React 等）由用户自己安装、版本由用户锁定，不在这里查。有发现就先在发版 PR 里写明影响与处置（升级、说明走不到、或者接受），评估之前不要直接升级。2026-09-24（9.2.0 首发前）：`pnpm audit --prod` 为 0（219 个运行时依赖）；全量审计的 14 条（6 high、8 moderate）都在开发依赖上——`documentation` 经 vitepress 的 vite／esbuild，`compensation/dashboard` 经 shadcn CLI 的 hono、qs、fast-uri、js-yaml——三个发布包的路径上没有。2026-10-01（五个发布包）：`pnpm audit --prod` 有 3 条，都是 `brace-expansion` 5.0.9（GHSA-qhr7-859c-m2p7、GHSA-6j4f-fj2g-mc7p 两条 high，GHSA-q2hr-2g5m-vwhr 一条 moderate，修复于 5.0.12），路径 wow-generator → ts-morph → @ts-morph/common → minimatch → brace-expansion；其余四个发布包没有。处置（用户批准）：`pnpm update -r brace-expansion` 把锁文件里的 brace-expansion 更新到 5.0.12——minimatch 10.2.6 依赖 `^5.0.8`，不用 override，也不动 ts-morph；之后 `pnpm install --frozen-lockfile` 通过，`pnpm audit --prod` 为 0。用户装 wow-generator 时按同一个范围解析，新装就拿到 5.0.12。2026-10-03（9.2.0 发版 PR）：`pnpm audit --prod` 为 0；全量审计 32 条（10 high、19 moderate、3 low）都在开发依赖上，五个发布包上没有（上面第二条命令输出 `published packages: no advisories`）。

2. 发版 PR：`pnpm set-version <version>`，更新版本表、文档和快照，合并。预发布版（`-rc.n`）只更新快照，版本表与文档留在上一个正式版（见「C」第 2 步）。
3. 创建 GitHub release（tag `v<version>`，指向 main 或 `release-x.y` 上的提交），release notes 按[「发布说明」](#发布说明)用模板写。
4. 等 `admission`、`preflight`；Maven 两路成功以后 `npm-deploy` 自动发布；等 `npm-smoke` 变绿，这次发布才算完成。

维护线 `release-x.y` 没有 push 触发，不影响发版：准入在 tag 上触发完整运行。给老版本线发补丁时，dist-tag 自动是 `release-x.y`，不会动 `latest`。

支持期（用户 2026-09-24 定）：全项目一个策略，就是 `SECURITY.md` 现有的写法，npm 包也适用——修复发在最新的稳定版本线上，旧版本线逐案评估。曾提议的「上一个次版本 3 个月安全修复」已撤回：Wow 几天就发一个次版本（8.11→8.16 用了六天），这个窗口意味着同时往很多条线回移修复。

## 发布说明

GitHub release 的正文就是变更记录，不另外维护 `CHANGELOG.md`。正文按 [RELEASE_NOTES_TEMPLATE.md](RELEASE_NOTES_TEMPLATE.md) 手写：Highlights、Breaking（TypeScript、Kotlin / JVM、Server behaviour、Pre-release changes）、Behaviour changes、每个发布包一节、JVM、完整 compare 链接。次版本的草稿可以先放在 `typescript/release-notes/v<版本>.md`，随 PR 一起评审（9.2.0 就是这样），发布后删掉。

**Breaking 必填**（用户 2026-09-24 定）：每个带破坏性改动的次版本，「Breaking」一节逐条列出这些改动，每条写清影响谁、怎么迁移（步骤，必要时前后代码）。这与文档建议用户用 `~` 锁在一个次版本上配套：用户是读了这一节才升级的。补丁版本不会有破坏性改动（准入拒绝），这一节写「None.」。

**Server behaviour**（Breaking 的一个小节）：v9 冻结既有聚合的 REST 行为、存储与线格式。用户批准的例外（记在 decisions 里，比如 D77）是破坏性的，只进 `x.Y.0`，在这一小节逐条写：改了什么、影响谁、**升级前**要做什么、**9.x 混跑**时新旧节点各怎么回答同一个请求。新节点需要的权限与启动步骤也写在这里。

**Behaviour changes 必填**：用户看得见、但**不破坏**任何人的行为变化——5xx 变 4xx、503 变成空结果、更清楚的错误文本、只对新索引生效的模板——写明要不要动手，新旧节点回答不同时写混跑的表现。会破坏某个用户的，不论多小，都进「Breaking」，不进这一节。没有就写「None.」。PR 描述里的「Behaviour changes」一节是这一节的原料，它不给 PR 加 `breaking-change`（「发版准入」第 3 条）。

**准入替你数**：`x.Y.0` 的 release 正文必须点名（`#编号`）上一个 tag 以来 main 上每个破坏性 PR——标题带 `!`、`BREAKING CHANGE:` 脚注、或带 `breaking-change` 标签（见「发版准入」第 3、4 条）。漏一个就拒绝并列出它。每个都写在「Breaking」里；只改了未发布代码的破坏，列在「Pre-release changes」一行里。

素材从三处来，都不能原样贴：

1. Draft a new release 页面上的 **Generate release notes**（Previous tag 选上一个 `v*` tag）。分类来自 `.github/release.yml`：带 `breaking-change` 标签的 PR 排在最前，`area: typescript`（pr-labeler 按 `typescript/**` 自动加）的 PR 归在「TypeScript Packages」。`breaking-change` 由 `pr-labeler.yml` 自动加：分支名以 `breaking/` 开头，标题是带 `!` 的 Conventional Commit（`type(scope)!: …`，与准入同一判据），或者描述按「发版准入」第 3 条声明了破坏（`.github/scripts/breaking-label.mjs`）；改标题、改描述时也会重新判断，只加不删。
2. 按提交整理。`--first-parent` 只走 main 上的合并提交，导入的 fetcher 历史不会混进来：

   ```bash
   PREV=v9.3.0 NEXT=HEAD                      # 上一个 tag、这次的 tag（或 HEAD）
   for pkg in $(node .github/scripts/publish-npm.mjs --list); do
     echo "## $pkg"; git log --first-parent --format='- %s' "$PREV..$NEXT" -- "typescript/${pkg#@ahoo-wang/}"
   done
   # 破坏性提交：标题带 ! 或者正文有 BREAKING CHANGE 脚注，Kotlin 与 TypeScript 都算（与准入同一判据）
   git log --first-parent --format='%h %s' "$PREV..$NEXT" | grep -E '^[0-9a-f]+ [a-z]+(\([^)]*\))?!:'
   git log --first-parent -E --grep='^BREAKING[ -]CHANGE:' --format='%h %s' "$PREV..$NEXT"
   # 带 breaking-change 标签的 PR（标题没写 ! 的破坏也在这里）
   gh pr list --repo Ahoo-Wang/Wow --state merged --label breaking-change \
     --search "merged:>=$(git log -1 --format=%cs "$PREV")" --json number,title --jq '.[] | "#\(.number) \(.title)"'
   ```

3. PR 描述。合并的 PR 里的「Release notes」「Breaking」「Behaviour changes」「Compatibility」各节是作者写给发布说明的原料（升级步骤、混跑时的表现），按上面的列表逐个读。PR 的「Breaking」进发布说明的「Breaking」；PR 的「Behaviour changes」先判断是否破坏了某个用户：破坏了就进「Breaking」，并给 PR 补上 `breaking-change` 标签，否则进「Behaviour changes」。

为什么不用 git-cliff 之类的生成器：说明里最要紧的 Highlights 和迁移步骤本来就要人写；生成器按提交出列表，而 `v9.1.5..v9.2.0` 里导入的 fetcher 提交正好都在 `typescript/` 下，按路径过滤不掉，还要再维护一套跳过规则和一个新的二进制依赖。GitHub 的 `release.yml` 已经在用、没有新依赖，调一下分类顺序就能给出按 Breaking 与 TypeScript 分好组的 PR 列表，够当素材。

## 出错时

- **准入失败**：日志里写了是哪条流水线、哪次运行。修好以后在那次运行上 Re-run failed jobs，再重跑 `admission`。
  - 「breaking changes … ship only in x.Y.0」：补丁版本里有破坏性提交或带 `breaking-change` 标签的 PR。标签贴错了就去掉标签再重跑；确实是破坏，就改发 `x.Y.0`。
  - 「the release notes do not name these breaking changes」：编辑 GitHub release 的正文，把列出的每个 PR 写进「Breaking」或它的「Pre-release changes」一行，再重跑 `admission`。「the tag has no GitHub release」：在 tag 上手动运行之前先建 release。
- **只有一路失败**（Maven 与 npm 目前并行，R3-13 未定）：只重跑失败的 job，不要重跑整个工作流。npm 这一路会跳过已经发布的包。`central-deploy` 的 `closeAndReleaseSonatypeStagingRepository` 不幂等：它失败时先到 Sonatype 查看 staging 仓库的状态，已经 release 的不要再发，只剩 staging 的在网页上手动 close/release 或 drop 后重跑。
- **`npm-smoke` 失败**：先看日志和运行页面顶部的注解是哪一步。
  - 「the registry does not serve …」：npm 在 5 分钟里还没给出新版本，多半是 registry 延迟。本机 `npm view <包>@<版本> version` 能查到以后，只重跑 `npm-smoke`（Re-run failed jobs）。查不到就回到 `npm-deploy` 的日志看是哪个包没发出去。
  - 「dist-tag … is …, not …」：包已经上去，但默认安装拿不到它，或者拿到的是别的版本。用 `npm dist-tag ls <包>` 核对，把 dist-tag 改对（命令同下面的回退），再重跑 `npm-smoke`。
  - 安装、import、require、样式表解析、bin（`--version`、`theme-check --help`）或类型检查失败：发布已经上线而且是坏的，按下一条处理。
- **镜像先于准入发出**：三个镜像工作流在 tag 创建时就推 `X.Y.Z`、`X.Y`（是最高的稳定版本时还有 `latest`），不等 `admission`、`preflight`。准入或 preflight 失败、这次发布作废时，镜像已经在三个仓库里：按下面「回退步骤」里的镜像一段把 `X.Y`、`latest` 指回上一个版本。
- **npm 发布以后发现问题**：发出去的版本不能覆盖；unpublish 只在 72 小时内、没人依赖时可行，而且这个版本号永远不能再用，还会让已有的锁文件装不上。所以**不 unpublish**，只有泄露了密钥或者发出了恶意内容才考虑，同时联系 npm 支持并发安全公告。能用的手段是移动 dist-tag、`npm deprecate`、发补丁。

### 发补丁还是回退

- **发补丁（默认）**：问题只影响部分用法或者有绕过办法，或者修复能在几个小时内合并并通过准入。按「日常发版」发 `x.y.(z+1)`，它会自动拿到原来的 dist-tag（`latest` 或 `release-x.y`），同时对坏版本执行下面的 `npm deprecate`。补丁里不能带破坏性改动（准入拒绝），修复本身需要破坏性改动时，先回退，再在下一个 `x.Y.0` 里修。
- **先回退、再发补丁**：`npm-smoke` 失败（装不上、导入不了、bin 不能运行），或者问题影响所有用户、会写坏数据、是安全问题，而修复不能很快发出。回退只改变**以后**的默认安装；锁文件里已经是坏版本的用户不受影响，`npm deprecate` 的警告是提醒他们的办法，所以两步都要做。
- Maven 这一路不能撤回，也不随 npm 回退。问题在服务端 Kotlin 代码时，按 Maven 的方式发补丁，npm 包是否跟着 deprecate 看它是否受影响。
- Docker 镜像（example、compensation、view store 服务端）的 `X.Y.Z` 不删、不覆盖；回退移动的是 `X.Y` 与 `latest`（见回退步骤最后一段）。

### 回退步骤

所有发布包**一起**回退：它们同版本发布，wow-react、wow-generator、wow-view-engine、wow-view-store 以 `~<版本>` 依赖 wow-client，wow-view-store 还以 `~<版本>` 依赖 wow-view-engine。只回退 wow-client 时，`latest` 上的其他包仍然要求坏版本或者更新的 wow-client，用户同时安装两个包就会遇到 peer 冲突。名单从 `PUBLISHED` 取，不手写。

维护者本机操作：npm 账号开了 2FA，Publishing access 禁止了 token，每条命令 npm 都会要 OTP（可信发布只管 `npm publish`）。

```bash
BAD=9.2.1                   # 坏版本
PREV=9.2.0                  # 同一个 dist-tag 上一个好的版本
TAG=latest                  # 坏版本拿到的 dist-tag：latest、next 或 release-x.y
PACKAGES=$(node .github/scripts/publish-npm.mjs --list)   # 在坏版本的 tag 上运行：git checkout --detach "v$BAD"
npm login
for pkg in $PACKAGES; do npm dist-tag ls "$pkg"; npm view "$pkg@$PREV" version; done   # 先核对：每个包的 $PREV 都存在
for pkg in $PACKAGES; do npm dist-tag add "$pkg@$PREV" "$TAG"; done
for pkg in $PACKAGES; do
  npm deprecate "$pkg@$BAD" "Broken release: <one-line reason>. Use $PREV or a later patch. https://github.com/Ahoo-Wang/Wow/releases/tag/v$BAD"
done
for pkg in $PACKAGES; do npm dist-tag ls "$pkg"; done                                  # $TAG 指向 $PREV
```

然后：

1. 编辑 GitHub release `v$BAD`，正文第一行加上 `> [!WARNING]` 说明原因和该用哪个版本。不删 release、不动 tag（tag 规则也不允许）：Maven 产物还挂在它上面。
2. 回退以后不要重跑这次发布的 `npm-smoke`：它检查 dist-tag，会按设计失败。下一个补丁的 `npm-smoke` 就是验证。
3. 修复后按「日常发版」发补丁。`publish-npm.mjs` 按最高的 `v*` tag 决定 dist-tag，补丁比坏版本高，会重新拿到 `$TAG`。
4. deprecate 打错了可以撤销：`npm deprecate "$pkg@$BAD" ""`。

按 dist-tag 分情况：

- **`latest`**：如上。**首发 9.2.0 没有上一个稳定版**：npm 不允许删除 `latest`，也不要把 `latest` 指向 rc，只能 deprecate 9.2.0 并尽快发 9.2.1。
- **`next`**（预发布版本，例如坏的 `9.2.0-rc.1`）：优先发下一个 rc（`rc.2`），对坏 rc 执行 `npm deprecate`；要立刻挡住，就 `TAG=next PREV=9.2.0-rc.0` 走上面的步骤。稳定版发布以后 `next` 停在最后一个 rc 上（见 E.4），不用管它。
- **Docker 镜像**：在本机（已登录 Docker Hub、GHCR、阿里云三个仓库）把坏版本移动过的浮动 tag 指回 `$PREV` 的同一份 manifest，不重新构建：

  ```bash
  MINOR=${BAD%.*}             # 9.2；坏版本是这条线第一个版本时没有可指回的 X.Y，只改 latest
  for registry in ahoowang ghcr.io/ahoo-wang registry.cn-shanghai.aliyuncs.com/ahoo; do
    for image in wow-example-server wow-compensation-server wow-view-store-server; do
      docker buildx imagetools create -t "$registry/$image:latest" -t "$registry/$image:$MINOR" "$registry/$image:$PREV"
    done
  done
  ```

  坏版本是老版本线的补丁时，它没有移动 `latest`（`.github/scripts/docker-latest.mjs` 只让最高的稳定版本打 `latest`，和 npm 的 dist-tag 同一条规则）：只把 `X.Y` 指回这条线上一个补丁，命令里去掉 `-t "$registry/$image:latest"`。没有上一个版本的镜像（首发 9.2.0 的 view store 服务端），不回退，尽快发补丁。

- **`release-x.y`**（老版本线的补丁，不会动 `latest`）：`TAG=release-x.y`，`PREV` 是这条线上一个补丁。坏版本是这条线第一个打了 `release-x.y` 的补丁时，没有可以指回的版本：对每个发布包执行 `npm dist-tag rm "$pkg" "release-x.y"` 并 deprecate，修好后的补丁会重新建这个 tag。无论哪种情况都不要碰 `latest`。

## peer 范围

发布包的 `peerDependencies` 引用 `catalog:peers`（`pnpm-workspace.yaml` 里的具名 catalog），开发依赖引用默认 catalog。fetcher 的范围是 `^5.1.5 || ^6.0.0`。9.2.0 是 `^5.1.5`（用户 2026-09-25 定，第二轮审查决定 5：首发前去掉了尚未发布、没人验证过的 `^6.0.0`）；fetcher 侧的 `downstream-wow.yml` 在 fetcher 6 上跑通 Wow 的测试后，9.2.1 放宽为现在的范围（#3896），放宽不破坏兼容，所以不必等 `x.Y.0`。fetcher 6.0 于 2026-10-04 发布，此后开发与测试用 6（默认 catalog 是 `^6.0.0`），下限由 `typescript.yml` 的 `fetcher-floor` 任务守着：`.github/scripts/fetcher-floor.mjs pin` 用 pnpm `overrides` 把工作区的每个 fetcher 钉到 `catalog:peers` 范围的下限（从那里读，现在是 5.1.5），`verify` 确认各包确实解析到它，然后跑 wow-client、wow-react、wow-generator、wow-view-store 的测试，再跑 `package-check.mjs --fetcher-floor`（使用方项目显式装下限版本的 fetcher peer）。`package` 任务、发布预检与 `npm-smoke` 不带这个参数，npm 装范围里最新的 fetcher。抬下限时这个任务跟着 `peers` 走，不用改。Renovate 对 `peers` 只做 `widen`，而且要在 Dependency Dashboard 里批准：新大版本出来时加进范围，永远不抬高下限。要抬下限，手工改 `peers`，而且只在 `x.Y.0` 里做。包检查会拒绝不从 `catalog:peers` 或 `workspace:` 取 peer 的发布包。

React 与 react-router 的下限是冒烟过的最低版本（R2-96，2026-10-03）：`react`、`react-dom` 是 `^19.0.0`，`react-router` 是 `^7.0.0 || ^8.0.0`。`^19.3.0` 原是默认 catalog 的开发版本，建工作区时（#3281）随 fetcher 的 catalog 进来、#3323 照抄进 `peers`，没人测过更低的版本；放宽不破坏兼容，所以在补丁版本里做。冒烟的做法：从 main 构建并 `pnpm pack` 五个包，在仓库之外的空目录里用 `--save-exact` 装这些包和 `react@19.0.0`、`react-dom@19.0.0`、`react-router@7.0.0`、`@types/react@19.0.0`、`@types/react-dom@19.0.0`（没有 peer 警告，没有 override）；使用方渲染 wow-react 的 Hook，以及 `MemoryViewStore` 上一个两字段定义的 `ViewHost`，用 `/react-router` 的 `useReactRouter()` 包在 `MemoryRouter` 里，`DataWorkbench` 画出来源给的行；在 node16、nodenext、bundler 下 `tsc` 检查（不跳过库检查），在 Node 里 `renderToString` 一次、在 jsdom 里客户端渲染一次。CI 不装下限版本：工作区测试用锁文件里的 React（开发 catalog），包检查让 npm 装 `peers` 范围里最新的。所以下限靠这条规则守着：用到 React 19.0 之后才有的 API、react-router 7.0 之后才有的 API，或者升级视图引擎的 React 依赖（Base UI、dnd-kit、react-markdown 等，它们各自声明 React 下限）时，在同一个 PR 里重跑这个冒烟，过不了就把下限抬到过得了的版本——抬下限只在 `x.Y.0` 里做。

peer 只放运行时真正加载、或公开声明真正引用的包（用户 2026-09-25 定，第二轮审查 P1-11）：wow-generator 只用 `@ahoo-wang/fetcher-openapi` 的类型，构建后不留痕迹，所以它是 devDependency；wow-react 从不导入 `@ahoo-wang/fetcher-eventstream`，那是 wow-client 的 peer，wow-react 不再声明。

TypeScript 不是 peer（用户 2026-09-25 定，第二轮审查 P1-15）：没有哪个包声明 `peerDependencies.typescript`，支持范围写在文档里。下限是 TypeScript 6；包检查（`package-check.mjs`，`package` 任务、发布预检与 `npm-smoke`）在同一个干净项目里用 TypeScript 6.0 和最新的 7.x 各编译一遍使用方代码。要抬下限，改 `package-check.mjs` 的 `TYPESCRIPT_VERSIONS` 和文档，只在 `x.Y.0` 里做。
