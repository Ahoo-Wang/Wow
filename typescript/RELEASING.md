# 发布手册

Maven 和 npm 用同一个版本号、同一个 `v*` tag 一起发布（见 [MIGRATION.md](MIGRATION.md)「发布策略」）。本文是维护者照做的手册：发布流水线怎么把关、日常发版和出错时怎么处理。

**9.2.0 已发布**（2026-10-03，tag `v9.2.0` → `7fe4934b5`，[GitHub release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.0)）：五个发布包在 npm 上的首发。它的发布说明就是那个 release，草稿已删。此后的补丁：

- **9.2.1**（2026-10-04，[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.1)）：服务端渲染修复、fetcher peers 放宽到 `^5.1.5 || ^6.0.0`、React peer 下限 `^19.0.0`。
- **9.2.2**（2026-10-04，[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.2)，[Packages Deploy](https://github.com/Ahoo-Wang/Wow/actions/runs/37180601238)）：视图引擎的稳健性修复与 Kotlin ABI 基线。`npm-smoke` 第一次两路都超时——npm 接受 wow-client 之后约 13 分钟才对外提供，当时等待上限是 5 分钟；重跑后全绿。之后等待上限改为 30 分钟，`npm-deploy` 也不再需要审批，改为等 Maven 两路成功（#3908）。

**9.3.0 已发布**（2026-10-09，tag `v9.3.0` → `e71384074`（#4025 的合并提交），[GitHub release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.3.0)，[Packages Deploy](https://github.com/Ahoo-Wang/Wow/actions/runs/37882720912)）：整体重构的次版本，按用户决定**不发 rc**——五个发布包自 9.2.3 起源码没有变化，rc 只发 npm 验证不到 JVM 侧；控制台试用改在 main 的本地构建上做（[MIGRATION.md](MIGRATION.md)「9.3.0 控制台试用记录」）。`admission`（27 个破坏性 PR 都点名）、`preflight`、`github-deploy`、`central-deploy`、`npm-deploy`（五个包都带 provenance，`latest: 9.3.0`）、两个 `npm-smoke` 全部成功；三个镜像的 `9.3.0`、`9.3`、`latest` 在 Docker Hub、ghcr、阿里云 digest 一致。发布说明就是那个 release，草稿已删。

下一个版本按「发版准入」第 3 条定：上一个 tag 以来没有破坏性改动就是下一个补丁版本；有就只能是下一个次版本（`x.Y.0`），准入拒绝补丁版本。最新发布是 `v9.9.0`（2026-10-12，[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.9.0)）。草稿开始写时放在 `release-notes/v<版本>.md`。

「发布包」在本文里一律指 `.github/scripts/publish-npm.mjs` 的 `PUBLISHED`，9.2.0 起是五个：wow-client、wow-react、wow-generator、wow-view-engine、wow-view-store。下面的循环都从 `node .github/scripts/publish-npm.mjs --list` 取包名，不在文中手写名单。

## 发布流水线

`package-deploy.yml` 在创建 GitHub release 时运行，也可以手动在一个 `v*` tag 上运行（Actions → Packages Deploy → Run workflow → Use workflow from → Tags → `v<version>`）。别的 ref 一律拒绝。所有 action 都按 commit SHA 锁定，Renovate 的 `github-actions` 组连同版本注释一起升级。

| Job              | 做什么                                                                                                                                                                                                                                                                                                                                                                                                          | 权限                                                                      |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `admission`      | ref 必须是 `v*` tag；tag = `gradle.properties` = 各 `package.json` 的版本；发版准入（`.github/scripts/release-admission.mjs`）                                                                                                                                                                                                                                                                                  | `contents: read`、`actions: write`、`pull-requests: read`，不接触任何密钥 |
| `preflight`      | `pnpm build:typescript` → 打出 npm tarball → 包检查（`.github/scripts/package-check.mjs`）→ `publish-npm.mjs --dry-run` → 上传 tarball；`./gradlew :check :integrationTest`、`publishToMavenLocal`（签名）→ 上传 Maven 产物                                                                                                                                                                                     | `contents: read`                                                          |
| `github-deploy`  | 发布到 GitHub Packages                                                                                                                                                                                                                                                                                                                                                                                          | `packages: write`                                                         |
| `central-deploy` | 发布到 Maven Central                                                                                                                                                                                                                                                                                                                                                                                            | Sonatype 凭据                                                             |
| `npm-deploy`     | 等 `github-deploy`、`central-deploy` 都成功后（environment `npm-publish`，不需要审批），发布 preflight 检查过的**那几个 tarball**：不安装、不构建，npm 固定为精确版本，OIDC 可信发布并带 provenance                                                                                                                                                                                                             | `id-token: write`，environment `npm-publish`                              |
| `npm-smoke`      | `npm-deploy` 成功以后，在 Node 22.12.0 和 24 上各跑一次 `package-check.mjs --registry`：等 registry 能给出这个版本（最多 20 次、间隔 15 秒），核对 dist-tag，然后在干净项目里从 npm 装每个发布包的 `<包>@<版本>`，import 每个入口（承诺给 CommonJS 的入口再 require 一次；视图引擎与视图存储只有 ESM）、解析视图引擎的样式表、运行 generator 的 `--version` 与 `wow-view-engine theme-check --help`、做类型检查 | `contents: read`，不接触任何密钥                                          |

同一个 ref 同时只跑一次发布（`concurrency`，排队而不取消）。每个 job 都有超时。

### 发版准入

准入回答的问题是「要发出去的这个提交本身是否通过了全部检查」，所以它看的是这个提交上的**完整运行**：

1. 提交必须在 `main` 或某条维护线 `release-x.y` 上，tag 不能指向没经过 PR 的提交。9.x 从 `v9` 发版，用它自己那份准入脚本。
2. `ci.yml` 在这个提交上有一次**手动触发（workflow_dispatch）的运行**成功，而且其中的 `ci-gate` 通过。手动触发的运行没有 diff 的基准，scope 会打开全部 job；push 运行只测那次 push 改到的部分，所以不算数。提交上还没有这样的运行时，准入自己在 release tag 上触发，然后等它们跑完（默认最多 90 分钟）。已有的运行（比如维护者事先触发过，或者上一次发布尝试触发的）直接复用；它失败了就要先重跑它的失败 job，再重跑这次发布。
3. 上一个 `v*` tag 以来有破坏性改动时，这次发布必须是 `x.Y.0`。破坏性改动有三种认法：提交标题带 `!`、提交正文有 `BREAKING CHANGE:` 脚注、提交所属的 PR 带 `breaking-change` 标签。标签是标题之外的那道网：pr-labeler 在标题带 `!`、PR 描述有 `BREAKING CHANGE:` 行、勾了 PR 模板的 **Breaking** 框、或者有一节标题恰好是 `## Breaking`／`## Breaking changes`（二、三级标题，可以带括号限定；`### Breaking down the work` 不算）而且正文不是 None／No／N/A 时自动加（`.github/scripts/breaking-label.mjs`）；PR 的 diff 从公开面记录里去掉或改动了一行时也自动加，这些记录是 Kotlin ABI 转储 `<模块>/api/<模块>.api`、API 报告 `typescript/*/test/api/*.api.md` 和公开名单 `typescript/*/test/surface/*.txt`。规则按行看：只有 `+` 行（新增）不加；任何 `-` 行都加，签名改动在 diff 里是一对 `-`／`+`，同样算。不算数的 `-` 行只有四种：空行、公开名单的 `#` 表头（里面的名字数随新增变化）、API 报告的 `//` 注释（发布标记和 `(undocumented)`，补 TSDoc 时会变；删掉的声明自己那一行照样算）、以及折叠空白后又作为 `+` 行出现在同一文件里的行（只是缩进或位置变了）。改名或移动的记录按它自己的 diff 判断：内容不变不加，删除整个记录等于删除每一行；GitHub 不给 patch 的超大 diff 只要有删除就加。某行的改动其实不破坏兼容时（比如只改了一条消息的文案），由维护者在最后一次 push 之后去掉标签并在 PR 里说明理由：labeler 每次 push 都会重跑、把标签加回来。评审发现作者没标的破坏时手工加。PR 描述里的 `## Behaviour changes` 一节**不加**标签：它是发布说明「Behaviour changes」的原料，那一节只放不破坏兼容的可见变化（见「发布说明」）。
4. `x.Y.0` 的 GitHub release 正文必须点名（`#编号`，HTML 注释里的不算：release 页面不显示注释）上一个 tag 以来 main 上（first parent，不含合并进来的 fetcher 历史）每个破坏性 PR；tag 还没有 release 时拒绝。漏掉的 PR 会逐条列在失败信息里：把它写进「Breaking」（只改了未发布代码的写进「Pre-release changes」那一行），编辑 release 正文后重跑 `admission`。

准入还认不出的情况，靠评审和发布说明的人工核对补上：**提交与 PR 对不上。** 准入靠 squash 合并在标题末尾加的 `(#编号)` 把提交对到 PR、再查标签。rebase 合并（ruleset 允许）的提交，或者合并时把 `(#编号)` 改掉、删掉的标题，对不到 PR，标签也就不起作用，只有提交自己的 `!` 或 `BREAKING CHANGE:` 脚注算数。带破坏的 PR 一律 squash 合并，标题里留着 `(#编号)`。

Gradle 流水线不在准入里：preflight 自己在这个提交上跑 `./gradlew :check :integrationTest`（单元、契约、集成测试），比回头查别的运行更直接。

为什么不选「要求上一个 tag 以来 main 上每次 push 运行都成功」：push 运行是裁剪过的，拼起来也只是近似覆盖；中途失败、后来修好的运行会永远挡住发布，除非再加一套「以后面的为准」的规则；维护线没有 push 触发，这条规则在那里根本不成立。在发版提交上跑一次完整运行，对 main 和维护线都一样，也不依赖合并节奏。

## 首发清单

9.2.0 的首发清单（A～F，含在补偿控制台上试用 rc 的 C′）已经做完。补偿控制台随 9.x 代码离开了 `v10`（D262、D266），清单的全文留在 `main` 分支的本文里。

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

   第一条查整个工作区的运行时依赖；第二条把每条发现归到发布包（`PUBLISHED`）上，只看它们装进用户项目的依赖（`dependencies` 及其传递依赖；视图引擎的 echarts、Base UI、dnd-kit 等也在内）。`pnpm audit --json` 每条发现只给一条示例路径，按路径前缀过滤会漏（9.2.0 之前的写法就漏了 wow-generator 的一条），所以按包名与版本问 `pnpm why`。peer（fetcher、React 等）由用户自己安装、版本由用户锁定，不在这里查。有发现就先在发版 PR 里写明影响与处置（升级、说明走不到、或者接受），评估之前不要直接升级。2026-09-24（9.2.0 首发前）：`pnpm audit --prod` 为 0（219 个运行时依赖）；全量审计的 14 条（6 high、8 moderate）都在开发依赖上——`documentation` 经 vitepress 的 vite／esbuild，9.x 补偿控制台经 shadcn CLI 的 hono、qs、fast-uri、js-yaml——三个发布包的路径上没有。2026-10-01（五个发布包）：`pnpm audit --prod` 有 3 条，都是 `brace-expansion` 5.0.9（GHSA-qhr7-859c-m2p7、GHSA-6j4f-fj2g-mc7p 两条 high，GHSA-q2hr-2g5m-vwhr 一条 moderate，修复于 5.0.12），路径 wow-generator → ts-morph → @ts-morph/common → minimatch → brace-expansion；其余四个发布包没有。处置（用户批准）：`pnpm update -r brace-expansion` 把锁文件里的 brace-expansion 更新到 5.0.12——minimatch 10.2.6 依赖 `^5.0.8`，不用 override，也不动 ts-morph；之后 `pnpm install --frozen-lockfile` 通过，`pnpm audit --prod` 为 0。用户装 wow-generator 时按同一个范围解析，新装就拿到 5.0.12。2026-10-03（9.2.0 发版 PR）：`pnpm audit --prod` 为 0；全量审计 32 条（10 high、19 moderate、3 low）都在开发依赖上，五个发布包上没有（上面第二条命令输出 `published packages: no advisories`）。

2. 发版 PR：`pnpm set-version <version>`，更新版本表、文档和快照，合并。预发布版（`-rc.n`）只更新快照，版本表与文档留在上一个正式版。
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
- **镜像先于准入发出**：`image-deploy.yml` 在 `package-deploy.yml` 的发布里推 V10 服务端镜像（`wow-ops-server`、`wow-view-store-server`），只打确切的版本号 `X.Y.Z`，不打 `X.Y` 与 `latest`（它们留在 9.x 线上，到 10.0.0 GA 为止）。准入或 preflight 失败、这次发布作废时，镜像已经在三个仓库里，但没有浮动 tag 指向它，不用回退。
- **npm 发布以后发现问题**：发出去的版本不能覆盖；unpublish 只在 72 小时内、没人依赖时可行，而且这个版本号永远不能再用，还会让已有的锁文件装不上。所以**不 unpublish**，只有泄露了密钥或者发出了恶意内容才考虑，同时联系 npm 支持并发安全公告。能用的手段是移动 dist-tag、`npm deprecate`、发补丁。

### 发补丁还是回退

- **发补丁（默认）**：问题只影响部分用法或者有绕过办法，或者修复能在几个小时内合并并通过准入。按「日常发版」发 `x.y.(z+1)`，它会自动拿到原来的 dist-tag（`latest` 或 `release-x.y`），同时对坏版本执行下面的 `npm deprecate`。补丁里不能带破坏性改动（准入拒绝），修复本身需要破坏性改动时，先回退，再在下一个 `x.Y.0` 里修。
- **先回退、再发补丁**：`npm-smoke` 失败（装不上、导入不了、bin 不能运行），或者问题影响所有用户、会写坏数据、是安全问题，而修复不能很快发出。回退只改变**以后**的默认安装；锁文件里已经是坏版本的用户不受影响，`npm deprecate` 的警告是提醒他们的办法，所以两步都要做。
- Maven 这一路不能撤回，也不随 npm 回退。问题在服务端 Kotlin 代码时，按 Maven 的方式发补丁，npm 包是否跟着 deprecate 看它是否受影响。
- Docker 镜像的 `X.Y.Z` 不删、不覆盖；10.x 的镜像没有浮动 tag，回退不动镜像。

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
- **`next`**（预发布版本，例如坏的 `9.2.0-rc.1`）：优先发下一个 rc（`rc.2`），对坏 rc 执行 `npm deprecate`；要立刻挡住，就 `TAG=next PREV=9.2.0-rc.0` 走上面的步骤。稳定版发布以后 `next` 停在最后一个 rc 上，不用管它。
- **Docker 镜像**：10.x 的镜像只有确切的版本号，没有要指回的浮动 tag；9.x 镜像的 `X.Y` 与 `latest` 由 `main` 上的镜像工作流打，回退也按 `main` 的 `typescript/RELEASING.md` 做。

- **`release-x.y`**（老版本线的补丁，不会动 `latest`）：`TAG=release-x.y`，`PREV` 是这条线上一个补丁。坏版本是这条线第一个打了 `release-x.y` 的补丁时，没有可以指回的版本：对每个发布包执行 `npm dist-tag rm "$pkg" "release-x.y"` 并 deprecate，修好后的补丁会重新建这个 tag。无论哪种情况都不要碰 `latest`。

## peer 范围

发布包的 `peerDependencies` 引用 `catalog:peers`（`pnpm-workspace.yaml` 里的具名 catalog），开发依赖引用默认 catalog。fetcher 的范围是 `^5.1.5 || ^6.0.0`。9.2.0 是 `^5.1.5`（用户 2026-09-25 定，第二轮审查决定 5：首发前去掉了尚未发布、没人验证过的 `^6.0.0`）；fetcher 侧的 `downstream-wow.yml` 在 fetcher 6 上跑通 Wow 的测试后，9.2.1 放宽为现在的范围（#3896），放宽不破坏兼容，所以不必等 `x.Y.0`。fetcher 6.0 于 2026-10-04 发布，此后开发与测试用 6（默认 catalog 是 `^6.0.0`），下限由 `ci.yml` 的 `fetcher-floor` 任务守着：`.github/scripts/fetcher-floor.mjs pin` 用 pnpm `overrides` 把工作区的每个 fetcher 钉到 `catalog:peers` 范围的下限（从那里读，现在是 5.1.5），`verify` 确认各包确实解析到它，然后跑 wow-client、wow-react、wow-generator、wow-view-store 的测试，再跑 `package-check.mjs --fetcher-floor`（使用方项目显式装下限版本的 fetcher peer）。`package` 任务、发布预检与 `npm-smoke` 不带这个参数，npm 装范围里最新的 fetcher。抬下限时这个任务跟着 `peers` 走，不用改。Renovate 对 `peers` 只做 `widen`，而且要在 Dependency Dashboard 里批准：新大版本出来时加进范围，永远不抬高下限。要抬下限，手工改 `peers`，而且只在 `x.Y.0` 里做。包检查会拒绝不从 `catalog:peers` 或 `workspace:` 取 peer 的发布包。

React 与 react-router 的下限是冒烟过的最低版本（R2-96，2026-10-03）：`react`、`react-dom` 是 `^19.0.0`，`react-router` 是 `^7.0.0 || ^8.0.0`。`^19.3.0` 原是默认 catalog 的开发版本，建工作区时（#3281）随 fetcher 的 catalog 进来、#3323 照抄进 `peers`，没人测过更低的版本；放宽不破坏兼容，所以在补丁版本里做。冒烟的做法：从 main 构建并 `pnpm pack` 五个包，在仓库之外的空目录里用 `--save-exact` 装这些包和 `react@19.0.0`、`react-dom@19.0.0`、`react-router@7.0.0`、`@types/react@19.0.0`、`@types/react-dom@19.0.0`（没有 peer 警告，没有 override）；使用方渲染 wow-react 的 Hook，以及 `MemoryViewStore` 上一个两字段定义的 `ViewHost`，用 `/react-router` 的 `useReactRouter()` 包在 `MemoryRouter` 里，`DataWorkbench` 画出来源给的行；在 node16、nodenext、bundler 下 `tsc` 检查（不跳过库检查），在 Node 里 `renderToString` 一次、在 jsdom 里客户端渲染一次。CI 不装下限版本：工作区测试用锁文件里的 React（开发 catalog），包检查让 npm 装 `peers` 范围里最新的。所以下限靠这条规则守着：用到 React 19.0 之后才有的 API、react-router 7.0 之后才有的 API，或者升级视图引擎的 React 依赖（Base UI、dnd-kit、react-markdown 等，它们各自声明 React 下限）时，在同一个 PR 里重跑这个冒烟，过不了就把下限抬到过得了的版本——抬下限只在 `x.Y.0` 里做。

peer 只放运行时真正加载、或公开声明真正引用的包（用户 2026-09-25 定，第二轮审查 P1-11）：wow-generator 只用 `@ahoo-wang/fetcher-openapi` 的类型，构建后不留痕迹，所以它是 devDependency；wow-react 从不导入 `@ahoo-wang/fetcher-eventstream`，那是 wow-client 的 peer，wow-react 不再声明。

TypeScript 不是 peer（用户 2026-09-25 定，第二轮审查 P1-15）：没有哪个包声明 `peerDependencies.typescript`，支持范围写在文档里。下限是 TypeScript 6；包检查（`package-check.mjs`，`package` 任务、发布预检与 `npm-smoke`）在同一个干净项目里用 TypeScript 6.0 和最新的 7.x 各编译一遍使用方代码。要抬下限，改 `package-check.mjs` 的 `TYPESCRIPT_VERSIONS` 和文档，只在 `x.Y.0` 里做。
