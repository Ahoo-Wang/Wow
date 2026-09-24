# 与 Wow 紧耦合的包迁往 Wow 仓

## 状态与依据

方案已与用户逐条确认（2026-09-23），经两轮自审，补充项均已并入。**执行中**，已完成的步骤、在飞 PR 和下一步见文末「进度」。

本文从 fetcher 仓 `docs/superpowers/specs/2026-09-23-wow-packages-migration-design.md` 复制而来（取自 Ahoo-Wang/fetcher#1899 分支 `bb0a9dabd`，含 2026-09-24 补的 `/fetcher` 子路径与 5.x 流水线两处），**此后以本仓这份为准**，迁移进度也只记在这里。用户 2026-09-24 定：放在 `typescript/MIGRATION.md`，不放 `docs/superpowers/`（那里在 Wow 被 `.gitignore` 忽略，且根目录 `AGENTS.md` 规定不再新增文件）。文中的 `#编号` 除 #2170、#3248、#3277 以外都指 fetcher 仓的 PR。

- Fetcher 基线：`2f47af0f7`（#1849）。Wow 基线：`8eb3fe580`（#3277），版本 `9.1.5`。
- 耦合度：2026-03 以来 wow 相关路径（`packages/wow`、`view-engine`、`generator`、`viewer`、`react/src/wow`、`react/src/dataMonitor`）共 547 个非合并提交，其中只有 13 个同时改了核心包源码（约 2.4%）；view-engine 的 341 个提交里只有 4 个动了别的包。
- npm 周下载（2026-09-23）：`fetcher-wow` 1464、`fetcher-generator` 1039、`fetcher-viewer` 970；`fetcher-view-engine` 从未发布。
- Wow 发版频率：2026-09 发了 15 个以上的版本，发布流水线跑 11～30 分钟。

## 目标与边界

**为什么迁**：这些包真正要兼容的是 **Wow 服务端的版本**。`generator-test.yml` 拿 Wow 8.10.8 和 8.11.5 两个镜像做矩阵，wow 包的类型契约来自 `wow-openapi` 和 `wow-query`。放进同一个仓库后，Kotlin 端的契约、TS 客户端和生成器可以在一个 PR 里改完，一起发布。fetcher 也回到本来的定位：一个通用的 HTTP 客户端。

**迁什么**：

| 原来                                                                                     | 迁移后                                                                                       | Wow 里的目录                  |
| ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | ----------------------------- |
| `@ahoo-wang/fetcher-wow`（`packages/wow`）                                               | `@ahoo-wang/wow-client`                                                                      | `typescript/wow-client`       |
| `packages/react/src/wow` 和 `test/wow`                                                   | `@ahoo-wang/wow-react`                                                                       | `typescript/wow-react`        |
| `@ahoo-wang/fetcher-view-engine`                                                         | `@ahoo-wang/wow-view-engine`                                                                 | `typescript/wow-view-engine`  |
| `@ahoo-wang/fetcher-generator`                                                           | `@ahoo-wang/wow-generator`（命令叫 `wow-generator`，`fetcher-generator` 作为别名保留到 v10） | `typescript/wow-generator`    |
| `.storybook`、`stories/view-engine`、`stories/react/WowQuery*`、`stories/shared`（复制） | 私有包                                                                                       | `typescript/storybook`        |
| `integration-test` 里 wow 和生成代码的用例、`src/generated`                              | 私有包                                                                                       | `typescript/integration-test` |
| `skills/fetcher-wow-cqrs`、`skills/fetcher-openapi-generator`                            | 并入 Wow 的 `skills/`                                                                        | —                             |
| `.claude/skills/shadcn`（仓库内副本）                                                    | 复制                                                                                         | `.claude/skills/shadcn`       |

**不迁**：

- `@ahoo-wang/fetcher-viewer`：已被 view-engine 取代，冻结在 `5.x`。
- `packages/react/src/dataMonitor`：#1207 专门为 viewer 顶栏的"数据监控"开关加的，使用方只有 viewer，随它留在 `5.x`。它的做法是浏览器每 30 秒轮询一次总数，只比较总数，多个标签页会重复通知，文案写死。替代方案是阶段 6 的服务端订阅（`packages/view-engine/docs/design/decisions.md` 仪表盘运维那一条）。

## 目录结构

pnpm 工作区的根放在 Wow 仓的**根目录**。这样 `compensation/dashboard` 和 `documentation` 才能进同一个工作区：dashboard 通过 `workspace:` 直接用 SDK 的源码，SDK 一改，dashboard 在同一个 PR 里就能测到。分组目录叫 `typescript/`，按工具链划界。里面的东西不都是前端（generator 是 Node 命令行工具，integration-test 在 Node 里跑），目录名也不用中文。

```
Wow/
├── build.gradle.kts · settings.gradle.kts · wow-*/ · example/ · test/   # 不动
├── package.json            # 私有的工作区根，只放脚本和开发依赖
├── pnpm-workspace.yaml     # 包列表 + catalog（合并 documentation 原有的配置）
├── pnpm-lock.yaml          # 全仓只有一份 JS 锁文件
├── tsconfig.base.json · eslint.config.js · .prettierrc
├── typescript/
│   ├── AGENTS.md           # TS 的规则，就近生效；根目录的 AGENTS.md 管 Kotlin
│   ├── MIGRATION.md        # 本文：迁移方案与进度
│   ├── wow-client/  wow-react/  wow-view-engine/  wow-generator/
│   ├── wow-view-store/     # 阶段 6：ViewStore 端口的 Wow 实现
│   ├── storybook/          # 私有
│   └── integration-test/   # 私有
├── view-store/             # 阶段 6：view-engine 的存储后端（Kotlin）
│   └── wow-view-store-api/  wow-view-store-domain/  wow-view-store-server/
├── compensation/dashboard/ # 纳入工作区，改用 workspace: 依赖，删掉它自己的锁文件
├── documentation/          # 纳入工作区；fetcher wiki 里 wow、generator、view-engine 的章节并进来
└── docs/compat-debt.md     # 兼容债务清单（见「兼容策略」）
```

`documentation` 部署时用的 `--shamefully-hoist`（#2170 为 mermaid 加的）不带进工作区。它会对全仓生效，把所有依赖提升到根目录，掩盖各个包漏声明的依赖。原计划改用 `public-hoist-pattern` 只提升 mermaid 相关的包；W1 实测（2026-09-24）不做任何提升，`docs:build` 与 `docs:dev` 下 mermaid 都正常渲染（documentation 直接声明了 `mermaid`），所以连 `public-hoist-pattern` 也不加。

## 依赖方向

- **依赖只能是单向的：Wow → fetcher。** Wow 里用到的 `@ahoo-wang/fetcher*` 一律通过 catalog 引用 npm 上已发布的版本，都写成 peer 依赖（现在已经是这样）。fetcher 的 CI 加一条检查：任何包都不允许依赖 `@ahoo-wang/wow-*`。
- **`wow-*` 首发时，fetcher 6.0 还没发布**，所以 peer 依赖写成 `^5.1 || ^6`。由此有两条约束：
  - fetcher 6.0 只做删除，不带核心 API 的破坏性改动（有的话留到 7.0），否则 `^6` 这个范围就不成立；
  - 迁移窗口里先发一个 fetcher 5.x 补丁（5.1.3），把 fetcher-react 对 fetcher-wow 的 peer 依赖标成可选（`peerDependenciesMeta`），并新增子路径 `/fetcher`（见下一条）。否则装 wow-react 会连带装上 fetcher-wow，项目里就有两份 Wow 类型和两套同名的查询 hook。
- **Wow 内部包之间**（比如 view-engine 依赖 wow-client）也用 peer 依赖，范围写 `workspace:~`，发布后是 `~x.y.z`，即同一个小版本内兼容。现在 view-engine 对 fetcher-wow 是普通依赖、范围是 `^`，迁移时一起改。
- 拆出去的 `wow-react` 只依赖 fetcher-react 的 `core` 和 `fetcher` 两处。`core` 早有子路径 `@ahoo-wang/fetcher-react/core`；`fetcher`（`useFetcher`、`useFetcherQuery` 等）原来只能从根入口拿到，而根入口的类型声明引用 `@ahoo-wang/fetcher-wow`、还导出一套与 wow-react 同名的查询 hook。所以第 0 步的 5.x 补丁新增子路径 `@ahoo-wang/fetcher-react/fetcher`（构建产物校验它不加载任何集成），**wow-react 只从 `/core` 与 `/fetcher` 两个子路径导入**，peer 依赖写 `^5.1.3 || ^6`。（2026-09-24 准备第 0 步时补上；运行时根入口对 fetcher-wow 只有 `import type`，不装它也不会找不到模块，问题只在类型层。）

## 发布策略

**Maven 和 npm 用同一个版本号、同一个 tag，每次发布一起升级。** 只要有任何改动，就整条线一起发。理由：

- Wow 现在的三十多个 Maven 模块本来就是一起升版本的；
- 版本号本身就表示兼容关系：`wow-client 9.2.3` 对应 Wow `9.2.3`；
- 分开发布就得长期维护兼容矩阵、两套 tag 和两套发版准入检查；
- 按现在的发版频率，纯 TS 的修复最多等一两天就能随下一个版本发出去。

- **版本号只在一个地方定义**：`gradle.properties`。升版本的脚本同时改写各个可发布包的 `package.json`，quality 检查校验两边一致。
- **破坏性改动只能放在 `x.Y.0` 发布**，Kotlin 和 TS 同一条规则。由**发版准入检查**把关：上一个 tag 以来如果有带 `!` 的提交，这次发布就必须是 `x.Y.0`。PR 上不拦，因为 Wow 只在 main 上开发。release notes 里单独列出"Breaking"一节，每一条都写明迁移方法。对照：Wow v9.1.1 里有 `refactor!: unify storage batching and harden shutdown (#3248)`，这正是这条规则要纠正的做法。
- **流程**：创建 release（`v*`）后先跑 preflight：
  - 校验 tag 版本 = gradle 版本 = 各包 `package.json` 的版本；
  - `./gradlew build allIntegrationTest`；
  - `pnpm -r build`；
  - 移植 `release-admission.mjs`，要求这个提交上的 `typescript-gate` 是绿的。

  通过后三路并行发布：GitHub Packages、Maven Central、npm。

- **npm 这一路**：
  - 走 OIDC 可信发布并带上 `--provenance`，不用长期有效的 token；
  - 要能重复执行：先用 `npm view <name>@<ver>` 查，已经发过的版本直接跳过，所以 Maven 成功、npm 失败时可以重跑补发；
  - 给老版本线发补丁时，加对应的 dist-tag（比如 `--tag v9.0`），避免覆盖 `latest`。
- **哪些包发布**：
  - 发布：`wow-client`、`wow-react`、`wow-generator`；
  - view-engine 稳定之前不发布：`wow-view-engine`、`wow-view-store`；
  - 永远不发布：`storybook`、`integration-test`。
- **还没稳定的 Maven 模块也不发布。** Wow 现在的规则是 `publishProjects = subprojects - exampleProjects - …`，除了服务端模块，其余子项目全部发布。要新增一个 `incubatingProjects` 集合并从中排除，`view-store/*` 先放进去。view-engine 宣布稳定那天，Kotlin 和 TS 两边同时从排除名单里拿掉。

## 兼容策略

**Wow v9 期间保持兼容旧版，到 v10 统一清理兼容债务。** v9 期间要兼容的有两项：

1. **生成器和客户端要能连 Wow 8.x 的服务端**（#1359 "support legacy and latest Wow query fields"）。
2. **已经标记弃用的 Condition API**：`packages/wow/src/query/condition.ts` 里大约 20 处 `@deprecated`（指引改用 `FilterExpression` 和 `filter.*`），以及 `filter.ts` 里的 `QueryField`。

记账方式：

- 弃用的 API 用 `@deprecated` 作标记，注释里写上 `Removed in v10.`；其他兼容代码（比如为 8.x 做的字段分支）用 `// compat(wow<9): <原因>` 标记，Kotlin 端同样适用。
- `docs/compat-debt.md` 里每一条写清楚：兼容的是什么、标记在哪、**换成什么**、v10 时怎么删。quality 检查核对标记和清单一一对应，替换方案是否写全靠人审。
- 必须写明替换路径的例子：`Condition` 虽然弃用了，但没弃用的 `queryable.ts`、`queryApi.ts` 还在引用它，生成器也把 `wow.api.query.Condition` 映射成 `Condition`（`wowTypeMapping.ts:31`），所以用户已经生成的代码用的就是这个弃用类型。v10 时这几处要一起换成 `FilterExpression`，迁移指南里写明需要重新生成代码。
- 清单里还要记：`fetcher-generator` 命令别名、8.x 旧服务端矩阵。
- 取消对 8.x 的支持属于破坏性改动，而且专门留给 v10。所以在 9.x 期间，即使是 `x.Y.0` 这种可以带破坏性改动的小版本，也不能删兼容代码。

## CI

### 原则

1. Gradle 那几条流水线不动，JS 部分另开一组。
2. **每条流水线都会被触发，由流水线里的一个 scope job 决定哪些 job 要跑**（从 fetcher 的 `ci-scope.mjs` 移植，遇到识别不了的路径就全部都跑）。最后用一个汇总 job `typescript-gate` 作为自主合并的依据；以后开启分支保护时，也要求它通过。现在 Wow 的 main 没有开分支保护，不会出现"检查一直卡在等待"的情况，但是否可以合并需要有一个统一的信号。
3. **契约测试直接对着 Wow 本仓源码构建出来的服务端跑。**

### Wow 仓

| 流水线                                                  | 什么时候跑                                                                                                                                                                    | 跑什么                                                                                                                                                                                                                                                                        |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `typescript.yml`（新增）                                | PR 和 main                                                                                                                                                                    | scope → quality（只检查改动文件的格式、eslint、各包的 `tsc --noEmit`、版本号是否一致、兼容标记是否与清单对应）→ unit（Node 22/24 × suite `[sdk, view-engine]`；Node 20 在 2026-04 已经停止维护）→ coverage 上传到 codecov（flag 用 `typescript`，走 OIDC）→ `typescript-gate` |
| `typescript-storybook.yml`（新增）                      | 改到 storybook、view-engine、wow-react 时                                                                                                                                     | `typecheck:stories`、`build-storybook` 并校验索引、在 Chromium 里跑 `test:storybook`                                                                                                                                                                                          |
| `typescript-contract.yml`（新增）                       | 同源契约：改到 `wow-*/**`、`schema/**`、`example/**`、wow-client、wow-generator、integration-test 时；旧版服务端矩阵：只在改到 wow-client、wow-generator、integration-test 时 | 同源契约：起 mongo → `./gradlew :example-server:installDist` 并启动 → 生成代码 → 对生成代码跑 `tsc` → 跑 integration-test。旧版服务端矩阵：`wow-example-server` 镜像 8.10.8 和 8.11.5，沿用 #1359。阶段 6 再加 view-store 的端口合同测试                                      |
| 契约测试的每夜任务（可选）                              | 定时                                                                                                                                                                          | 拿 npm 上已发布的 wow-client 和 generator，对着 main 最新构建的服务端跑                                                                                                                                                                                                       |
| `dashboard-test.yml`、`compensation-deploy.yml`（调整） | 原有触发条件，再加上 wow-client、wow-react                                                                                                                                    | 在仓根目录安装、用根目录的锁文件做缓存；构建改成 `pnpm --filter <dashboard>... build`                                                                                                                                                                                         |
| `documentation-deploy.yml`（调整）                      | 原有触发条件，再加上 `typescript/**`                                                                                                                                          | 见「文档站与 Storybook」                                                                                                                                                                                                                                                      |
| `package-deploy.yml`（扩展）                            | 创建 release 时                                                                                                                                                               | 加一个 `npm-deploy` job，见「发布策略」                                                                                                                                                                                                                                       |

renovate 把 `@ahoo-wang/fetcher*` 归成一组来升级。

### fetcher 仓

- `ci.yml` 的 suite 只剩 `core`；删掉 `generator-test.yml`；`integration-test` 只保留核心包的用例。decorator、fetcher、openai 的用例继续拿 wow-example-server 镜像当普通 HTTP 后端，这种测试时的依赖可以接受。
- 新增 `downstream-wow.yml`：改到 Wow 会用到的核心包时触发（fetcher、decorator、eventstream、react/core、openapi）。它 checkout Wow，通过 `pnpm overrides` 把依赖链接到这次 PR 的构建产物，然后跑 Wow 的 JS 单测和类型检查。一开始只作提示，不作为必须通过的检查。
- 发版准入检查要求的五条流水线（`ci.yml`、`quality.yml`、`build-storybook.yml`、`integration-test.yml`、`generator-test.yml`）分支过滤都加上 `5.x`，否则 5.x 分支的提交没有这些 push 运行，发版准入检查就过不去（`release-admission.mjs` 的 `requiredWorkflows`；3′ 删掉 `generator-test.yml` 时要从这张表里一起拿掉）。
- fetcher 6.0 发布以后，`5.x` 的补丁一律加 `--tag v5` 发布（现在的 `publish-npm.sh` 没有指定 dist-tag），否则会把 `latest` 改回 5.x。

### 耗时预期

现在 view-engine 的 PR 整体要跑 10～20 分钟：最长的 view-engine 单测 job 要 8～10.8 分钟，再加上按账号共享的 runner 池里的排队，一次实测排了 9.5 分钟。迁移以后，只改 view-engine 的 PR 大约跑 6 个 job，不再是现在的约 15 个，整体降到 9～11 分钟。要进一步缩短最长的那条路径，需要做下面几件事（与迁移无关，可以单独排期）：

- view-engine 单测拆成 3 片并行；
- 只在 Node 24 上收集覆盖率；
- Storybook 交互测试拆成 2 片，并缓存 Playwright 浏览器；
- 修一个 scope 的漏洞：包里的 `docs/**/*.md` 现在不算纯文档。`6e79f3059` 只改了 `decisions.md` 的一行，却跑满了整套 CI。

## 文档站与 Storybook

Wow 文档站（wow.ahoo.me，VitePress）已经这样挂了一份 dokka：放在 `/dokka/`，导航里有"API"入口并用 `target: '_blank'` 打开，sitemap 里也手动加了。Storybook 照这个做法挂到 `/storybook/`。

- 导航：中英文的导航栏都在"API"旁边加 `{ text: 'Storybook', link: '/storybook/', target: '_blank' }`。sitemap 的 `transformItems` 里补上这一条。
- 文档正文链接到某个故事时，用站内相对路径 `/storybook/?path=/docs/<id>`。
- 构建：Storybook 在 VitePress 构建完之后，直接输出到 `documentation/docs/.vitepress/dist/storybook`，不拷进 `public/`。
- 部署：拆成三个 job 并行构建，即 `dokka`、`storybook`、`vitepress`，再由 `assemble-and-deploy` 汇总部署。
- PR 检查：放进 `typescript.yml` 的 scope 体系，改到 `documentation/`、`typescript/storybook`、`wow-view-engine`、`wow-react` 时运行。内容：
  - 构建 VitePress 和 Storybook；
  - **跨站链接检查**：文档正文里每一个 `/storybook/?path=…` 链接，都必须能在 Storybook 的 `index.json` 里找到对应的故事；
  - VitePress 自己的死链检查跳过 `/storybook/` 前缀。
- fetcher 这边保留自己的 Storybook（http、events、storage、react 的故事）。迁走的故事各留一个"已迁移"的占位页，链接到 `wow.ahoo.me/storybook/` 上的对应故事。

## ViewStore 宿主

参照 `compensation/`：一个基于 Wow 的完整子系统单独占一个分组目录，Kotlin 模块按 api、domain、server 拆分。

- 目录叫 `view-store/`，与 view-engine 里的 `ViewStore` 端口同名，一眼能看出它是这个端口的实现。叫 `view-engine/` 会让人以为引擎本体在这里。
- TS 适配器单独做一个包 `typescript/wow-view-store`，不作为 view-engine 的子路径导出：
  - 端口是可插拔的，自己实现后端的用户不应该被迫装上 Wow 服务的客户端；
  - 它的客户端代码由 wow-generator 从这个服务的 OpenAPI 生成，和适配器放在一起；
  - 同一条版本线一起发布，多一个包几乎没有额外成本。
- 其余细节到阶段 6 再展开，先各记一行线索：
  - 部署形态：独立服务，还是也允许作为模块嵌进业务服务；
  - 可见性按 owner / space / tenant 划分；
  - 权限预取；
  - 配置存成不透明的 JSON；
  - 为了读到自己刚写的数据，是否要等 SNAPSHOT 阶段；
  - 创建操作的尽力去重（2026-09-19 已定：尽力而为即可）；
  - 服务端订阅（替代 dataMonitor）。

## 时机与步骤

**时机**：view-engine 阶段 3、4 收口以后（阶段 3 剩余项合并，阶段 3 与 4 一起审查、重构都已合并），阶段 5 开始之前。迁移窗口里连续做完第 0～3 步，阶段 5 起直接在 Wow 里开发，阶段 6 的存储后端也就原生地落在 Wow 里。（原定停在阶段 4 之前；阶段 4 在检查点写下前已做完并在 fetcher 合并，#1863，用户 2026-09-23 把停点挪到阶段 4 之后。）

- 不等 view-engine 开发完再迁：否则阶段 6 的一个功能要横跨两个仓库，阶段 7（文档）和 8（skills）写完还得改一遍路径。
- 不现在就迁：批 C、D 还在进行中，迁移改动会和阶段审查的重构混在一段历史里，而且会和阶段 3 抢额度。
- 不做"先迁 wow-client、view-engine 留在 fetcher"的过渡：这样依赖方向会暂时反过来。

**检查点**：根目录 `AGENTS.md` 的「Migration Checkpoint」一节和 view-engine 的 `docs/design/todo.md` 都写了：阶段 3 收口就停下，远端出现 tag `wow-migration-base` 以后，要迁走的路径全部冻结。第 3′ 步删除这两处。

**开始前要满足三个条件**：没有碰迁移路径的开着的 PR（与迁移路径无关的 PR 可以留着，用户 2026-09-24 定，见 view-engine 的 D28）；本地没有未推送的分支（包括三个故事场景分支 customer、trade-order、product-pricing）；worktree 都清理干净。

| 步骤 | 仓库    | 内容                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ---- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0    | fetcher | 发 5.x 补丁 5.1.3：fetcher-react 对 fetcher-wow 的 peer 依赖标成可选、新增子路径 `/fetcher`；发版准入要求的五条流水线分支过滤加上 `5.x`。合并后在 main 上打 tag `wow-migration-base`，并从同一个提交拉出 `5.x` 分支。**从这一刻起冻结要迁走的路径**                                                                                                                                                                                                |
| 1    | Wow     | 搭好根目录工作区、工具链和 CI 骨架（`typescript.yml` 和 gate），把 dashboard 和 documentation 并进来，复制 `.claude/skills/shadcn`，新增 `typescript/AGENTS.md`                                                                                                                                                                                                                                                                                    |
| 2    | Wow     | 从 `wow-migration-base` 带历史迁入 wow-client、wow-react、wow-generator 和 integration-test：改包名、peer 依赖范围和版本号；接上契约测试（同源 + 8.x 旧服务端矩阵）；新增 npm 发布 job、兼容债务清单、发版准入检查、`incubatingProjects`                                                                                                                                                                                                           |
| 3    | Wow     | 迁入 view-engine 和 storybook，重建 `view-engine-legacy` tag；文档站接入 Storybook；fetcher wiki 的对应章节并进 Wow 文档                                                                                                                                                                                                                                                                                                                           |
| 3′   | fetcher | 删掉已迁走的路径、viewer、dataMonitor、对应的故事和 `generator-test.yml`；suite 只剩 core；新增依赖方向检查和 `downstream-wow.yml`；删掉根目录 `AGENTS.md` 和 view-engine `todo.md` 里的迁移检查点。合并前确认 `git diff wow-migration-base..HEAD -- <已迁走的路径>` 为空。**合并后解除冻结；main 在 6.0 之前不发布**，这期间 fetcher 的修复从 `5.x` 分支发补丁                                                                                    |
| 4a   | 两边    | Wow 发出**首个稳定版**之后，fetcher 发 6.0。首个稳定版的判据：①wow-client、wow-react、wow-generator 发出第一个正式（非预发布）版本；②这个版本上的契约测试全部通过，包括 8.x 矩阵；③`wow-project-template/client` 已经切换到新包，并且端到端跑通。6.0 发布当天：wiki 里 wow 和 generator 的页面改成指向 Wow 文档的跳转页；viewer 的使用指南标注"仅适用于 5.x"；对 fetcher-wow、fetcher-generator 执行 `npm deprecate`（对外操作，执行前向用户确认） |
| 4b   | 两边    | view-engine 正式发布后：停止维护 `5.x`，对 fetcher-viewer 执行 `npm deprecate`；Wow 把 view-engine 和 view-store 从 `incubatingProjects` 与 npm 的排除名单里拿掉                                                                                                                                                                                                                                                                                   |

**带历史迁移**：在一个临时的 fetcher 克隆里跑 `git filter-repo`（本机已通过 Homebrew 安装 2.47.0），用 `--path-rename` 映射到 `typescript/…`，用 `--message-callback` 把提交信息里的 `(#1234)` 改写成 `(Ahoo-Wang/fetcher#1234)`，否则这些编号在 Wow 里会链接到别的 PR。**Wow 仓现在只允许 squash 和 rebase 合并**（`allow_merge_commit: false`）。squash 会把历史压成一个提交，rebase 要重放上千个提交。所以第 2、3 步的导入 PR 需要**临时开启 merge commit**。用户已于 2026-09-23 开启；W3 合并后由用户关掉。开启期间，其余 PR 一律显式用 `gh pr merge --squash` 合并。

## 任务分派

两个仓库各开一个主会话，工作目录分别放在各自仓库的 worktree 里。记忆和仓库内的 skills 都按目录生效，跨仓库写文件也会碰到权限提示，所以不从一个会话同时改两个仓库。只有在改动面不重叠、而且额度允许时才派子代理（Opus 5.5）并行。

| 任务                                                                                                                                                                                                                                    | 仓库 / 会话    | 依赖                 | 能否并行                                                                          |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- | -------------------- | --------------------------------------------------------------------------------- |
| P0 上下文：把 view-engine 相关记忆复制到 Wow 的项目记忆目录（`-Users-ahoo-work-ahoo-git-Wow`，合并进它已有的 `MEMORY.md`），fetcher 这边留指针；本文复制到 Wow（最终位置 `typescript/MIGRATION.md`），此后以 Wow 那份为准并在那里记进度 | fetcher 会话   | 阶段 3 收口          | —                                                                                 |
| F1 = 第 0 步                                                                                                                                                                                                                            | fetcher 会话   | P0                   | 与 W1 并行                                                                        |
| W1 = 第 1 步                                                                                                                                                                                                                            | Wow 会话       | P0                   | 与 F1 并行                                                                        |
| W2 = 第 2 步（导入历史 → 契约测试 → 发布与规则，拆成 2～3 个 PR）                                                                                                                                                                       | Wow 会话       | F1（tag）、W1        | 串行                                                                              |
| W3 = 第 3 步（view-engine 与 storybook）                                                                                                                                                                                                | Wow 会话       | W2                   | 串行                                                                              |
| W4 = 第 3 步里的文档迁移（wiki 章节并入 Wow 文档，中英文）                                                                                                                                                                              | Wow 子代理     | W2                   | 与 W3 并行，改动面不重叠                                                          |
| F2 = 第 3′ 步                                                                                                                                                                                                                           | fetcher 会话   | W2、W3 合并          | —                                                                                 |
| T1 = 4a 的判据③：切换 `wow-project-template/client`                                                                                                                                                                                     | 模板仓库的会话 | Wow 首个稳定版       | 只切这一个；CoSky、PrajnaBot、ai 等其他下游项目不在本次范围（用户 2026-09-23 定） |
| F3 = 4a（fetcher 6.0）                                                                                                                                                                                                                  | fetcher 会话   | T1                   | —                                                                                 |
| F4 = 4b                                                                                                                                                                                                                                 | fetcher + Wow  | view-engine 正式发布 | —                                                                                 |

每个任务按照"本地门禁逐条看退出码 → 开 PR → 自审 → 合并"推进。导入历史的 PR 只能用 merge commit 合并，其余 PR 照常 squash。进度记在 Wow 那份文档里，fetcher 的 PR 描述链接回去。

## 已核对的前提

- 依赖成环：viewer 有 29 个文件引用 fetcher-wow，`dataMonitor` 也引用了它。两者都不迁，只留在 `5.x`，并在第 3′ 步从 main 删除，所以迁移后不会出现跨仓的环。
- `react/src/wow` 的相对引用只指向 `core`、`fetcher`，两者都已经从 fetcher-react 公开导出。
- `generator-test.yml` 的 8.10.8、8.11.5 矩阵由 #1359 建立，这两个版本分别对应旧版和新版查询字段。
- Wow 的 `package-deploy.yml` 已经有 preflight 和三路发布的结构，可以直接在上面扩展。
- Wow 的 main 分支没有开分支保护，只允许 squash 和 rebase 合并。
- 本机的下游项目：`wow-project-template/client`、`CoSky/dashboard`、`PrajnaBot/client`、`ai/client` 用了 fetcher-wow 或 fetcher-generator，脚本里写的是 `fetcher-generator generate`；这几个项目都没有用 dataMonitor。

## 进度

> 每合并一个迁移 PR 就更新这一节，保证任何人读到这里都能接着做。

### 已完成

| 步骤         | 内容                                                                                                 | PR                |
| ------------ | ---------------------------------------------------------------------------------------------------- | ----------------- |
| P0           | fetcher 的项目记忆复制进 Wow 的项目记忆（见记忆 `fetcher-memories-imported`）                        | —（fetcher 会话） |
| W1 = 第 1 步 | 根目录工作区、工具链、CI 骨架，dashboard 与 documentation 并入，shadcn skill，`typescript/AGENTS.md` | Wow #3281         |

W1 的具体做法（W2 起沿用）：

- 根目录私有工作区：`package.json`（只放脚本与开发工具）、`pnpm-workspace.yaml`（成员 `typescript/*`、`compensation/dashboard`、`documentation`；catalog 收纳两者全部依赖，版本不变；并入 documentation 原有的 `allowBuilds`）、唯一的 `pnpm-lock.yaml`（以 dashboard 原锁文件为底，直接依赖的解析版本不变）、`tsconfig.base.json`、`eslint.config.js`、`.prettierrc`；
- `.prettierignore` 用白名单：prettier 只管 `typescript/`、根目录 JS 配置、`.github/scripts/*.mjs`、`typescript*.yml`，dashboard（双引号）、documentation（4 空格）和全仓 Markdown/YAML 保留各自风格；
- 删除 dashboard 与 documentation 各自的锁文件和 `documentation/pnpm-workspace.yaml`；documentation 标 `private`；
- `typescript.yml`：`scope`（`.github/scripts/ci-scope.mjs`，Kotlin/Gradle/dashboard/文案路径跳过，识别不了的全跑；push 到 main 用 `before..sha`）→ `quality`（CI 脚本测试、改动文件格式、eslint、`tsc`）→ `unit`（Node 22/24，占位，包迁入后自动生效）→ `docs`（改到 documentation 时构建 VitePress）→ `typescript-gate`；
- `dashboard-test.yml`、`compensation-deploy.yml`、`documentation-deploy.yml` 改为在根目录安装、用根锁文件缓存；去掉 `--shamefully-hoist`；
- `.claude/skills/shadcn` 从 fetcher 复制（`.gitignore` 放行 `/.claude/skills/`）；`typescript/AGENTS.md`；根 `AGENTS.md` 补工作区与 CI 说明。
- 已知遗留：`documentation/test/markdown-pages.test.mjs` 在 main 上就失败（vitepress-plugin-llms 1.14 只给首页注入提示），与迁移无关，已另开任务；修好后再把 `node --test` 加进 `docs` job。

### 在飞

- F1 = 第 0 步（fetcher 会话）：Ahoo-Wang/fetcher#1899（发 5.1.3：fetcher-wow 可选 peer、`/fetcher` 子路径、5.x 流水线），合并后打 tag `wow-migration-base` 并推 `5.x`。
- Wow 侧暂无在飞 PR。

### 下一步

1. **W2 等 fetcher 上出现 tag**：`git ls-remote --tags https://github.com/Ahoo-Wang/fetcher.git wow-migration-base` 有输出才开始（fetcher 会话会在 #1899 合并、5.1.3 发布、打 tag 并推 `5.x` 后通知）。开工前先把步骤和会碰到的仓库操作列给用户确认。
2. W2 要点（除「时机与步骤」表里的内容外）：wow-react 只从 `@ahoo-wang/fetcher-react/core` 与 `@ahoo-wang/fetcher-react/fetcher` 导入，迁入时改掉 `../core/index.js`、`../../fetcher/index.js` 这类相对引用，对 fetcher-react 的 peer 范围写 `^5.1.3 || ^6`；在 `ci-scope.mjs` 里为新目录（契约测试涉及的 `schema/`、`example/`、`gradle.properties` 版本校验等）重新分类并补测试。
3. #1899 合并到 fetcher main 后，与 main 上的方案原文核对一次，有差异就同步进本文。
