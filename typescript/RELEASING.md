# 发布手册

Maven 和 npm 用同一个版本号、同一个 `v*` tag 一起发布（见 [MIGRATION.md](MIGRATION.md)「发布策略」）。本文是维护者照做的手册：发布流水线怎么把关、首发怎么做、日常发版和出错时怎么处理。

**下一个版本是 9.2.0。** `v9.1.5` 以来有带 `!` 的提交（#3298 以及三个发布包在首发前的若干个，改的都是还没发布的代码），发版准入会拒绝 9.1.6。9.2.0 本来也适合做首发：它新增了一条 npm 产品线。

## 发布流水线

`package-deploy.yml` 在创建 GitHub release 时运行，也可以手动在一个 `v*` tag 上运行（Actions → Packages Deploy → Run workflow → Use workflow from → Tags → `v<version>`）。别的 ref 一律拒绝。所有 action 都按 commit SHA 锁定，Renovate 的 `github-actions` 组连同版本注释一起升级。

| Job              | 做什么                                                                                                                                                                                                                                                                      | 权限                                               |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| `admission`      | ref 必须是 `v*` tag；tag = `gradle.properties` = 各 `package.json` 的版本；发版准入（`.github/scripts/release-admission.mjs`）                                                                                                                                              | `contents: read`、`actions: write`，不接触任何密钥 |
| `preflight`      | `pnpm build:typescript` → 打出 npm tarball → 包检查（`.github/scripts/package-check.mjs`）→ `publish-npm.mjs --dry-run` → 上传 tarball；`./gradlew build allIntegrationTest`、`publishToMavenLocal`（签名）→ 上传 Maven 产物                                                | `contents: read`                                   |
| `github-deploy`  | 发布到 GitHub Packages                                                                                                                                                                                                                                                      | `packages: write`                                  |
| `central-deploy` | 发布到 Maven Central                                                                                                                                                                                                                                                        | Sonatype 凭据                                      |
| `npm-deploy`     | 在 environment `npm-publish` 里等维护者审批，然后发布 preflight 检查过的**那几个 tarball**：不安装、不构建，npm 固定为精确版本，OIDC 可信发布并带 provenance                                                                                                                | `id-token: write`，environment `npm-publish`       |
| `npm-smoke`      | `npm-deploy` 成功以后，在 Node 22.12.0 和 24 上各跑一次 `package-check.mjs --registry`：等 registry 能给出这个版本（最多 20 次、间隔 15 秒），核对 dist-tag，然后在干净项目里从 npm 装 `<包>@<版本>`，import 与 require 每个入口、运行 generator 的 `--version`、做类型检查 | `contents: read`，不接触任何密钥                   |

同一个 ref 同时只跑一次发布（`concurrency`，排队而不取消）。每个 job 都有超时。

### 发版准入

准入回答的问题是「要发出去的这个提交本身是否通过了全部检查」，所以它看的是这个提交上的**完整运行**：

1. 提交必须在 `main` 或某条维护线 `release-x.y` 上，tag 不能指向没经过 PR 的提交。
2. `typescript.yml`、`typescript-contract.yml`、`typescript-storybook.yml` 在这个提交上各有一次**手动触发（workflow_dispatch）的运行**成功，而且其中的 `typescript-gate`、`typescript-contract-gate`、`typescript-storybook-gate` 通过。手动触发的运行没有 diff 的基准，scope 会打开全部 job；push 运行只测那次 push 改到的部分，所以不算数。提交上还没有这样的运行时，准入自己在 release tag 上触发，然后等它们跑完（默认最多 90 分钟）。已有的运行（比如维护者事先触发过，或者上一次发布尝试触发的）直接复用；它失败了就要先重跑它的失败 job，再重跑这次发布。
3. 上一个 `v*` tag 以来有破坏性提交时，这次发布必须是 `x.Y.0`。

Gradle 流水线不在准入里：preflight 自己在这个提交上跑 `./gradlew build allIntegrationTest`（单元、契约、集成测试），比回头查别的运行更直接。

为什么不选「要求上一个 tag 以来 main 上每次 push 运行都成功」：push 运行是裁剪过的，拼起来也只是近似覆盖；中途失败、后来修好的运行会永远挡住发布，除非再加一套「以后面的为准」的规则；维护线没有 push 触发，这条规则在那里根本不成立。在发版提交上跑一次完整运行，对 main 和维护线都一样，也不依赖合并节奏。

## 首发清单

首发分两步：先由维护者在本机手工发 `9.2.0-rc.0`（dist-tag `next`，没有 provenance），让三个包名在 npm 上存在，才能给它们配置 Trusted Publisher；再由 CI 带 provenance 发 `9.2.0`，作为第一个 `latest`。

**时机（用户 2026-09-25 定）**：三个包和 Wow 9.2.0 服务端**一起**发，不先单发；并且**等补偿控制台重构全部完成（[view-engine-rebuild.md](../compensation/dashboard/docs/design/view-engine-rebuild.md) 批 0～7，旧页面都被引擎接管）之后再发**，连 `9.2.0-rc.0` 也在那之后。用户原话：「这样能提前发现真实环境问题、验证真实 API。」控制台完成后，rc 试用（C′）只剩核对 npm 产物，真实 API 已经在控制台上验证过。

- **为什么**：包版本跟 Wow 走，文档已写「需要 Wow 9.2.0+」的能力（`BEFORE_NOW`/`AFTER_NOW`、查询配置改名、违规码），先发客户端会让用户拿不到对应的服务端。补偿控制台批 1～4 是 wow-client、wow-react 最真实的用法，它暴露的形状问题要在首发前改掉。
- **范围冻结**：从 2026-09-25 到发布，三个包只收两类改动：补偿控制台暴露的问题的修复，以及只加不改的接口（违规码、N5 能力描述符）。不再做结构性重构。
- **视图引擎一起首发**（用户 2026-09-25 定：「视图引擎完成后一起发」）：9.2.0 发**四个**包，发版也等视图引擎完成。完成的判据：N5 能力描述符已接上（引擎按它做定义准入）；主题重构 D46 的各批已落地；第二轮全面审查通过。发版 PR 里把 `typescript/wow-view-engine` 从 `publish-npm.mjs` 的 `HELD_BACK` 挪到 `PUBLISHED`，四个包一起配置 Trusted Publisher，首发清单 A 的各项也要对它逐条做一遍（元数据、公开面快照、peer、包检查、npm-smoke）。

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
- [x] 版本范围（用户 2026-09-24 定）：兼容性页「版本范围」（中英文）是唯一写建议的地方——安装前在项目 `.npmrc` 加 `save-prefix=~`，或者 `--save-exact`，因为次版本可以带破坏性改动；各包 README 与快速开始只用一句话链接过去，安装命令不写版本号。「支持期」一节指向 `SECURITY.md`。
- [x] fetcher peer 下限：fetcher 5.1.4 发到 npm 以后、发 9.2.0 之前，把 `pnpm-workspace.yaml` 里 `catalog:peers` 和默认 catalog 的 fetcher 下限抬到 `^5.1.5`（fetcher-react 同样），并让 `.github/scripts/package-check.mjs` 在 fetcher 自身声明的类型诊断（现在只作为 `upstream` 打印）上也失败。2026-09-24 完成：下限为 `^5.1.5 || ^6.0.0`；fetcher 的类型诊断现在让包检查失败，只放过 `ALLOWED_FETCHER_DIAGNOSTICS` 逐条列出的已知诊断，已知诊断不再出现时也失败。5.1.3 在 node16 下报 TS1479（CJS 声明 `require` 到 ESM 声明），5.1.4 通过。
- [x] fetcher 5.1.5（`Response` 全局扩展的 getter → readonly 属性）：把下限抬到 `^5.1.5`，删掉包检查里的放行。2026-09-24 完成：5.1.5 发到 npm 后，main 上的全量 CI 因放行过期而失败（包检查从 npm 装到 5.1.5），当天把下限抬到 `^5.1.5 || ^6.0.0`，`ALLOWED_FETCHER_DIAGNOSTICS` 清空。2026-09-25 按第二轮审查决定 5 去掉 `^6.0.0`，两个 catalog 都是 `^5.1.5`，见「peer 范围」。5.1.4 的 fetcher-eventstream 在 `responses.d.ts` 与 `responses.d.cts` 里都用 getter 扩展全局 `Response`，同一次编译里同时有 ESM 与 CJS 消费者时，`contentType`、`isEventStream` 报 TS2300。
- [x] 发布工程（P1）：发布说明模板 [RELEASE_NOTES_TEMPLATE.md](RELEASE_NOTES_TEMPLATE.md) 与 `.github/release.yml` 的分类（Breaking 在前、TypeScript 单列），标题带 `!` 的 PR 自动加 `breaking-change`；`npm-deploy` 之后的 `npm-smoke` 从 npm 装包冒烟（同一套 fetcher 诊断放行）；「出错时」的回滚手册。
- [x] 文档（R4）：兼容性矩阵、快速开始、错误处理、认证、SSR/Node、CI 重新生成、排障；包 README 写「随 Wow 9.2.0 发布」，站点与 README 的 TypeScript 样例由 `documentation/test/typescript-samples.test.mjs` 对构建产物做类型检查。
- [ ] 视图引擎进首发：`HELD_BACK` → `PUBLISHED`；元数据与 README、`homepage`、keywords、`engines.node`、peer 范围、`.d.cts`/ESM 形状、公开面快照、包检查与 `npm-smoke` 都覆盖第四个包（用户 2026-09-25 定）。
- [ ] 发版 PR `chore(release): prepare 9.2.0-rc.0`：`pnpm set-version 9.2.0-rc.0`，按根 `AGENTS.md` 更新 README 版本表、文档和 openapi 快照，`pnpm check:versions`。

### B. 仓库设置（维护者在 GitHub 上操作，一次）

1. environment `npm-publish`：只允许 `v*` tag 部署，需要审批。只有一位维护者时 `prevent_self_review` 必须是 `false`，否则没人能批准自己触发的发布。

   ```bash
   gh api -X PUT repos/Ahoo-Wang/Wow/environments/npm-publish --input - <<'JSON'
   {
     "wait_timer": 0,
     "prevent_self_review": false,
     "reviewers": [{ "type": "User", "id": 4384159 }],
     "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
   }
   JSON
   gh api -X POST repos/Ahoo-Wang/Wow/environments/npm-publish/deployment-branch-policies \
     -f name='v*' -f type=tag
   ```

   网页操作等价：Settings → Environments → New environment `npm-publish` → Required reviewers 勾选并添加 `Ahoo-Wang` → Deployment branches and tags 选 Selected branches and tags → Add deployment branch or tag rule → Ref type 选 Tag，名称 `v*`。

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

1. 确认 npm 账号是组织 `ahoo-wang` 的成员、有发布权限、开了 2FA；本机 `npm -v` 不低于 11.5.1，`node -v` 不低于 22.12.0。
2. A 里的发版 PR 合并以后，在它的合并提交上打 tag 并推送。**不创建 GitHub release**：release 会触发 Maven 发布，rc 只发 npm。

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

5. 核对：`npm view @ahoo-wang/wow-client@9.2.0-rc.0`，另外两个包同样；`npm dist-tag ls @ahoo-wang/wow-client` 应当只有 `next: 9.2.0-rc.0`。在临时项目里 `npm i @ahoo-wang/wow-client@next @ahoo-wang/wow-react@next @ahoo-wang/wow-generator@next react`，再 `npx wow-generator --version`。然后删掉临时 clone。

### C′. 在补偿控制台上试用 rc（发 `latest` 的前置条件）

`9.2.0-rc.0` 发到 npm 以后，把补偿控制台（`compensation/dashboard`）依赖的三个包从工作区换成 npm 上的 rc，端到端跑通：生成、类型检查、lint、构建、单元测试、浏览器测试，再对着真实的补偿服务端走查一遍；然后在一个空目录里用 `@next` 逐字照快速开始做一遍（第 6 步）。**任何一步退出码不为 0，或者走查发现问题，都不发 9.2.0。** 控制台同时用 wow-client、wow-react 和 wow-generator，是这三个包在仓库里唯一的真实应用（integration-test 与 storybook 只是测试）（用户 2026-09-25 定：「不需要在 wow-project-template 中使用，补偿控制台就是真实案例」；[MIGRATION.md](MIGRATION.md) 第 4a 步判据③）。

试用在一个**永不合并**的临时分支上做。main 上的控制台始终用 `workspace:*`，9.2.0 发布后删掉这个分支。

控制台还依赖 `@ahoo-wang/wow-view-engine`，它还在 `HELD_BACK`、没有发到 npm，所以试用里它留在工作区、从源码构建：控制台就是它的真实使用方，这样试的正是它以后作为 npm 包使用 wow-client 的样子。它对 wow-client 的 peer 与 devDependency 都是 `workspace:~`，不处理就会从工作区另解析出一份 wow-client，与控制台的 rc 成了两份，试用就不干净。所以用根目录的 pnpm `overrides` 把整个工作区的 wow-client、wow-react、wow-generator 一律指向 npm 上的 rc，只构建视图引擎，不构建这三个已发布的包。

1. 分支。在一个新的 clone 或 worktree 里从 rc 的 tag 开分支，**不要运行 `pnpm build:typescript`**，也不要构建 wow-client、wow-react、wow-generator：它们的 `dist` 不存在，万一依赖仍然指向工作区，后面的构建会直接失败，而不是悄悄用上本地代码。

   ```bash
   git fetch origin --tags
   git worktree add ../wow-rc-trial -b chore/compensation-9.2.0-rc.0 v9.2.0-rc.0
   cd ../wow-rc-trial
   ```

2. 换成 npm 上的 rc。在根目录的 `pnpm-workspace.yaml` 末尾加上 `overrides`（这个工作区的 pnpm 设置都写在这里，根 `package.json` 没有 `pnpm` 字段）。它会把每个工作区成员里这三个包的说明符，包括控制台的 `workspace:*` 和视图引擎的 `workspace:~`，都换成精确的 rc，所以控制台的 `package.json` 不用改：

   ```yaml
   overrides:
     '@ahoo-wang/wow-client': 9.2.0-rc.0
     '@ahoo-wang/wow-react': 9.2.0-rc.0
     '@ahoo-wang/wow-generator': 9.2.0-rc.0
   ```

   ```bash
   pnpm install --no-frozen-lockfile                       # overrides 改了，锁文件要跟着更新
   pnpm --filter @ahoo-wang/wow-view-engine build          # 只构建视图引擎；它不用 wow-react
   ```

   tag 上工作区包的版本也是 `9.2.0-rc.0`，所以要确认 pnpm 真的从 registry 取包。pnpm 10 的 `link-workspace-packages` 默认是 `false`，仓库没有 `.npmrc`，`pnpm-workspace.yaml` 也没有改它，所以 override 里不带 `workspace:` 的版本号一律按 registry 解析（2026-09-25 核对：rc 发布之前，把 override 写成与工作区同版本的 `9.1.5`，`pnpm install` 直接报 `ERR_PNPM_FETCH_404 GET https://registry.npmjs.org/@ahoo-wang%2Fwow-react`，没有去链接 `typescript/*`；再把 override 指向 `pnpm pack` 出来的三个 tarball，下面四项核对全部符合，视图引擎和控制台都构建通过）。装完核对：

   ```bash
   pnpm list -r --depth 0 @ahoo-wang/wow-client @ahoo-wang/wow-react @ahoo-wang/wow-generator
   # 每一行都应是 …@9.2.0-rc.0；出现 …@link:../../typescript/… 就是还在用工作区
   pnpm why -r @ahoo-wang/wow-client | tail -1
   # Found 1 version of @ahoo-wang/wow-client（只按工作区链接解析时这条命令没有输出）
   realpath compensation/dashboard/node_modules/@ahoo-wang/wow-client \
     typescript/wow-view-engine/node_modules/@ahoo-wang/wow-client
   # 两行完全相同，都落在 node_modules/.pnpm/@ahoo-wang+wow-client@9.2.0-rc.0…/ 下，而不是 typescript/wow-client
   ls -d typescript/*/dist                                  # 只有 typescript/wow-view-engine/dist
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

   - `generate` 不用 `package.json` 里的 `generate` 脚本，那个脚本读的是开发集群的地址。`git diff` 应当没有差异。有差异时，在 main 上用工作区的生成器（先 `pnpm --filter @ahoo-wang/wow-generator build`）对同一个服务端再生成一次：工作区也有同样的差异，说明提交的产物过期了，在 main 上重新生成提交；只有 npm 上的生成器才有的差异，就是打包问题。
   - 不用 `pnpm --filter wow-compensation-dashboard... build`：这个过滤器会连带构建控制台的工作区依赖，而试用只允许构建视图引擎，它已在第 2 步单独构建，这里直接构建控制台自己即可。`coverage` 与 `test:browser` 同 `dashboard-test.yml`。
   - `test:browser` 在 `127.0.0.1:4174` 起构建好的 preview，接口由用例里的 `page.route` 桩住，不连服务端；它验证的是 rc 包在真实浏览器里的渲染和交互。

5. 对着真实服务端走查。服务端的 `spring.web.resources.static-locations` 是 `file:./compensation/dashboard/dist/`，从仓库根目录启动时直接提供上一步构建的控制台，生产构建的 `VITE_API_BASE_URL` 是 `/`，请求就落在同一个服务端上。先跑自动冒烟，退出码为 0：

   ```bash
   WOW_COMPENSATION_URL=http://127.0.0.1:18083 pnpm --dir compensation/dashboard test:browser
   ```

   设了 `WOW_COMPENSATION_URL` 时 Playwright 只跑 `e2e/real-server/`、不起 preview：它自己写入两条失败执行，直接打开「失败执行（预览）」`/executions`，断言真实的行渲染出来、按处理器加一个条件后只剩那一行；再打开「待重试」与「已到重试时间」，断言服务端按自己的时钟接受 `BEFORE_NOW`／`AFTER_NOW`（新写入的两条在前者、不在后者）；全程没有 4xx、5xx 与页面错误。它补上第 4 步 `test:browser` 打桩测不到的那一半——rc 包对真实服务端的查询。然后人工走查：打开 `http://127.0.0.1:18083/`，首页两类聚合都有数字，`/active` 列表里有第 3 步写入的记录，打开详情、历史，浏览器控制台没有错误，网络面板没有 4xx、5xx。

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

   2. 在仓库外的空目录里，逐字照[快速开始](../documentation/docs/zh/guide/typescript/quick-start.md)第 2～5 步操作，唯一的改动是给三个 Wow 包加上 `@next`：先按「版本范围」写 `.npmrc` 的 `save-prefix=~`，`npm init -y && npm pkg set type=module`，然后是页面上的两条 `pnpm add`（`@ahoo-wang/wow-client@next`、`@ahoo-wang/wow-generator@next`，其余照抄），页面上的 `tsconfig.json`，第 3 步的生成命令，第 4、5 步的 `src/cart.ts`、`src/main.ts`。
   3. `pnpm exec tsc -p tsconfig.json` 与 `node dist/main.js` 退出码都为 0，输出的三行与页面一致（`SNAPSHOT: cart … v1`、`items: [ { productId: 'book-1', quantity: 2 } ]`、`carts holding book-1: 1`）；`pnpm exec wow-generator --version` 是 rc；`node_modules/@ahoo-wang/fetcher-openapi` 不存在（生成器不再以它为 peer，第二轮审查 P1-11）。页面上的 `typescript` 装到的是最新的 7.x；再 `pnpm add -D typescript@~6.0.0` 把 `tsc` 重跑一遍，退出码也为 0，下限 6.0 同样照页面走通（P1-15）。页面上有一处照做不通，就是文档缺陷，与代码缺陷一样挡发布。
   4. wow-react：控制台已经在第 4、5 步用 rc 的 wow-react 跑过单元测试、浏览器测试和真实服务端走查；第 5 步走查时，列表、分页和详情页的请求都经 wow-react 的 Hook 发出，确认它们在网络面板里各只发一次、切换筛选时旧请求被取消（状态为 canceled）。

7. 记录与重来。把结果记进 [MIGRATION.md](MIGRATION.md)「进度」，写明两台服务端的 Wow 版本（补偿服务端、示例服务端都从 rc 的 tag 构建）。发现问题就在 Wow 的 main 上修复，发 `9.2.0-rc.1`，从新 tag 开新分支，从第 1 步重来。分支可以推到远端留证，但**不开 PR、不合并**；9.2.0 发布以后删掉它（`git push origin --delete chore/compensation-9.2.0-rc.0`），停掉两个服务端，`docker rm -f wow-rc-mongo wow-rc-example-mongo`。

### D. 配置 Trusted Publisher（三个包各一次）

1. npmjs.com → 包 → Settings → Trusted Publisher → GitHub Actions：
   - Organization or user：`Ahoo-Wang`
   - Repository：`Wow`
   - Workflow filename：`package-deploy.yml`
   - Environment name：`npm-publish`
2. 同一页 Publishing access 选 **Require two-factor authentication and disallow tokens**。之后只能通过 OIDC，或者本人加 2FA 发布。

### E. CI 发布 `9.2.0`

1. 前提：C′（补偿控制台试用）在最后一个 rc 上通过，A 里的 fetcher peer 下限已处理。发版 PR `chore(release): 9.2.0`：`pnpm set-version 9.2.0`，更新版本表与文档，合并。
2. GitHub → Releases → Draft a new release：tag `v9.2.0`（在 main 上新建），release notes 按下文[「发布说明」](#发布说明)用模板手写：`v9.1.5..HEAD` 里有约 1370 个从 fetcher 导入的提交，不能直接用自动生成的说明（R3-29）；`--first-parent` 只剩 main 上的约百个合并提交。三个包在 npm 上是首发，`v9.1.5` 以来它们的 `!` 提交（#3324、#3326、#3328、#3329、#3330、#3332、#3360、#3361）改的都是还没发布的代码，所以「Breaking」一节写的是**相对 `@ahoo-wang/fetcher-wow`、`fetcher-generator`、`fetcher-react` 的迁移**：包名、入口、Condition 查询移到 `/legacy`、生成器 CLI 与文件名等，逐条给出步骤，详细内容链接[迁移指南](../documentation/docs/zh/guide/typescript/migration.md)。#3298 只影响还没发布的 view-engine，列在「Not published」下。发布 release。
3. `admission` 触发三条完整运行并等待，`preflight` 跑完后 `github-deploy`、`central-deploy` 直接开始，`npm-deploy` 等审批：确认 Maven Central 那一路成功以后再在运行页面 Review deployments → `npm-publish` → Approve。
4. 核对：`npm-smoke` 两个 job 都绿；npmjs.com 上三个包的 9.2.0 显示 Provenance 徽章；`npm dist-tag ls @ahoo-wang/wow-client` 显示 `latest: 9.2.0`、`next: 9.2.0-rc.0`。
5. 在运行页面重跑 `npm-deploy`（Re-run failed jobs 或 Re-run job），三个包都应输出「already on npm; skipped」，验证幂等。

### F. 发布以后

1. 在 [MIGRATION.md](MIGRATION.md)「进度」里记下首发。
2. 翻转文档状态：`documentation/docs/{en,zh}/guide/typescript/` 的 `index.md`、`compatibility.md`「发布状态」、`quick-start.md` 的提示框和 `troubleshooting.md` 的 `E404` 一行，以及 `reference/typescript/index.md`，把「尚未上 npm／not yet on npm」改成已发布；包 README 已冻结在 tarball 里，只写了「随 Wow 9.2.0 发布」，不用改。然后在一个空目录里照[快速开始](../documentation/docs/zh/guide/typescript/quick-start.md)从 npm 安装、生成、编译一遍，确认页面上的安装命令能用。
3. 删掉 C′ 的临时分支 `chore/compensation-9.2.0-rc.0`（不合并，main 上的控制台保持 `workspace:*`）。按 MIGRATION「下一步」，再对 `fetcher-wow`、`fetcher-generator` 执行 `npm deprecate`（对外操作，先问用户）。

## 日常发版

1. 依赖审计。每次发版前在发版提交上运行，首发的 `9.2.0-rc.0`、`9.2.0` 也一样：

   ```bash
   pnpm install --frozen-lockfile
   pnpm audit --prod    # 工作区的运行时依赖，包括三个发布包的 dependencies
   pnpm audit --json | node -e '
     const { advisories } = JSON.parse(require("fs").readFileSync(0, "utf8"));
     const hits = Object.values(advisories).flatMap(a => a.findings.flatMap(f => f.paths))
       .filter(path => /^typescript__wow-(client|react|generator)>/.test(path));
     console.log(hits.join("\n") || "published packages: no advisories");
     process.exitCode = hits.length ? 1 : 0;'
   ```

   第二条连同开发依赖一起查，只看三个发布包的路径（它们的 peer 在工作区里是开发依赖）。有发现就先在发版 PR 里写明影响与处置（升级、说明走不到、或者接受），评估之前不要直接升级。2026-09-24（9.2.0 首发前）：`pnpm audit --prod` 为 0（219 个运行时依赖）；全量审计的 14 条（6 high、8 moderate）都在开发依赖上——`documentation` 经 vitepress 的 vite／esbuild，`compensation/dashboard` 经 shadcn CLI 的 hono、qs、fast-uri、js-yaml——三个发布包的路径上没有。

2. 发版 PR：`pnpm set-version <version>`，更新版本表、文档和快照，合并。
3. 创建 GitHub release（tag `v<version>`，指向 main 或 `release-x.y` 上的提交），release notes 按[「发布说明」](#发布说明)用模板写。
4. 等 `admission`、`preflight`；Maven 两路成功以后批准 `npm-deploy`；等 `npm-smoke` 变绿，这次发布才算完成。

维护线 `release-x.y` 没有 push 触发，不影响发版：准入在 tag 上触发完整运行。给老版本线发补丁时，dist-tag 自动是 `release-x.y`，不会动 `latest`。

支持期（用户 2026-09-24 定）：全项目一个策略，就是 `SECURITY.md` 现有的写法，npm 包也适用——修复发在最新的稳定版本线上，旧版本线逐案评估。曾提议的「上一个次版本 3 个月安全修复」已撤回：Wow 几天就发一个次版本（8.11→8.16 用了六天），这个窗口意味着同时往很多条线回移修复。

## 发布说明

GitHub release 的正文就是变更记录，不另外维护 `CHANGELOG.md`。正文按 [RELEASE_NOTES_TEMPLATE.md](RELEASE_NOTES_TEMPLATE.md) 手写：Highlights、Breaking、每个发布包一节、没发布的包、JVM、完整 compare 链接。

**Breaking 必填**（用户 2026-09-24 定）：每个带 TypeScript 发布包破坏性改动的次版本，「Breaking」一节逐条列出这些改动，每条写清影响谁、怎么迁移（步骤，必要时前后代码）。这与文档建议用户用 `~` 锁在一个次版本上配套：用户是读了这一节才升级的。补丁版本不会有破坏性改动（准入拒绝），这一节写「None.」。

素材从两处来，都不能原样贴：

1. Draft a new release 页面上的 **Generate release notes**（Previous tag 选上一个 `v*` tag）。分类来自 `.github/release.yml`：带 `breaking-change` 标签的 PR 排在最前，`area: typescript`（pr-labeler 按 `typescript/**` 自动加）的 PR 归在「TypeScript Packages」。`breaking-change` 由 `pr-labeler.yml` 自动加：分支名以 `breaking/` 开头，或者标题是带 `!` 的 Conventional Commit（`type(scope)!: …`，`.github/scripts/breaking-label.mjs`，与准入同一判据；改标题时也会重新判断，只加不删）。
2. 按提交整理。`--first-parent` 只走 main 上的合并提交，导入的 fetcher 历史不会混进来：

   ```bash
   PREV=v9.2.0 NEXT=v9.3.0                    # 上一个 tag、这次的 tag（或 HEAD）
   for pkg in wow-client wow-react wow-generator; do
     echo "## $pkg"; git log --first-parent --format='- %s' "$PREV..$NEXT" -- "typescript/$pkg"
   done
   # 破坏性提交：标题带 ! 或者正文有 BREAKING CHANGE 脚注（与准入同一判据）
   git log --first-parent --format='%h %s' "$PREV..$NEXT" -- typescript/ | grep -E '^[0-9a-f]+ [a-z]+(\([^)]*\))?!:'
   git log --first-parent -E --grep='^BREAKING[ -]CHANGE:' --format='%h %s' "$PREV..$NEXT" -- typescript/
   ```

为什么不用 git-cliff 之类的生成器：说明里最要紧的 Highlights 和迁移步骤本来就要人写；生成器按提交出列表，而 `v9.1.5..v9.2.0` 里导入的 fetcher 提交正好都在 `typescript/` 下，按路径过滤不掉，还要再维护一套跳过规则和一个新的二进制依赖。GitHub 的 `release.yml` 已经在用、没有新依赖，调一下分类顺序就能给出按 Breaking 与 TypeScript 分好组的 PR 列表，够当素材。

## 出错时

- **准入失败**：日志里写了是哪条流水线、哪次运行。修好以后在那次运行上 Re-run failed jobs，再重跑 `admission`。
- **只有一路失败**（Maven 与 npm 目前并行，R3-13 未定）：只重跑失败的 job，不要重跑整个工作流。npm 这一路会跳过已经发布的包。`central-deploy` 的 `closeAndReleaseSonatypeStagingRepository` 不幂等：它失败时先到 Sonatype 查看 staging 仓库的状态，已经 release 的不要再发，只剩 staging 的在网页上手动 close/release 或 drop 后重跑。
- **`npm-smoke` 失败**：先看日志和运行页面顶部的注解是哪一步。
  - 「the registry does not serve …」：npm 在 5 分钟里还没给出新版本，多半是 registry 延迟。本机 `npm view <包>@<版本> version` 能查到以后，只重跑 `npm-smoke`（Re-run failed jobs）。查不到就回到 `npm-deploy` 的日志看是哪个包没发出去。
  - 「dist-tag … is …, not …」：包已经上去，但默认安装拿不到它，或者拿到的是别的版本。用 `npm dist-tag ls <包>` 核对，把 dist-tag 改对（命令同下面的回退），再重跑 `npm-smoke`。
  - 安装、import、require、`--version` 或类型检查失败：发布已经上线而且是坏的，按下一条处理。
- **npm 发布以后发现问题**：发出去的版本不能覆盖；unpublish 只在 72 小时内、没人依赖时可行，而且这个版本号永远不能再用，还会让已有的锁文件装不上。所以**不 unpublish**，只有泄露了密钥或者发出了恶意内容才考虑，同时联系 npm 支持并发安全公告。能用的手段是移动 dist-tag、`npm deprecate`、发补丁。

### 发补丁还是回退

- **发补丁（默认）**：问题只影响部分用法或者有绕过办法，或者修复能在几个小时内合并并通过准入。按「日常发版」发 `x.y.(z+1)`，它会自动拿到原来的 dist-tag（`latest` 或 `release-x.y`），同时对坏版本执行下面的 `npm deprecate`。补丁里不能带破坏性改动（准入拒绝），修复本身需要破坏性改动时，先回退，再在下一个 `x.Y.0` 里修。
- **先回退、再发补丁**：`npm-smoke` 失败（装不上、导入不了、bin 不能运行），或者问题影响所有用户、会写坏数据、是安全问题，而修复不能很快发出。回退只改变**以后**的默认安装；锁文件里已经是坏版本的用户不受影响，`npm deprecate` 的警告是提醒他们的办法，所以两步都要做。
- Maven 这一路不能撤回，也不随 npm 回退。问题在服务端 Kotlin 代码时，按 Maven 的方式发补丁，npm 包是否跟着 deprecate 看它是否受影响。

### 回退步骤

三个包**一起**回退：它们同版本发布，wow-react 与 wow-generator 以 `~<版本>` 依赖 wow-client。只回退 wow-client 时，`latest` 上的 wow-react 仍然要求坏版本或者更新的 wow-client，用户同时安装两个包就会遇到 peer 冲突。

维护者本机操作：npm 账号开了 2FA，Publishing access 禁止了 token，每条命令 npm 都会要 OTP（可信发布只管 `npm publish`）。

```bash
BAD=9.2.1                   # 坏版本
PREV=9.2.0                  # 同一个 dist-tag 上一个好的版本
TAG=latest                  # 坏版本拿到的 dist-tag：latest、next 或 release-x.y
PACKAGES='@ahoo-wang/wow-client @ahoo-wang/wow-react @ahoo-wang/wow-generator'
npm login
for pkg in $PACKAGES; do npm dist-tag ls "$pkg"; npm view "$pkg@$PREV" version; done   # 先核对：三个包的 $PREV 都存在
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
- **`release-x.y`**（老版本线的补丁，不会动 `latest`）：`TAG=release-x.y`，`PREV` 是这条线上一个补丁。坏版本是这条线第一个打了 `release-x.y` 的补丁时，没有可以指回的版本：对三个包执行 `npm dist-tag rm "$pkg" "release-x.y"` 并 deprecate，修好后的补丁会重新建这个 tag。无论哪种情况都不要碰 `latest`。

## peer 范围

发布包的 `peerDependencies` 引用 `catalog:peers`（`pnpm-workspace.yaml` 里的具名 catalog），开发依赖引用默认 catalog。fetcher 的范围是 `^5.1.5`（用户 2026-09-25 定，第二轮审查决定 5）：首发前去掉了尚未发布、没人验证过的 `^6.0.0`。fetcher 6.0 发布以后，先让三条 TypeScript 流水线和包检查在 6.0 上跑通（默认 catalog 临时指向 6.0），再在一个补丁版本里把 `catalog:peers` 放宽为 `^5.1.5 || ^6.0.0`；放宽不破坏兼容，所以不必等 `x.Y.0`。Renovate 对 `peers` 只做 `widen`，而且要在 Dependency Dashboard 里批准：新大版本出来时加进范围，永远不抬高下限。要抬下限，手工改 `peers`，而且只在 `x.Y.0` 里做。包检查会拒绝不从 `catalog:peers` 或 `workspace:` 取 peer 的发布包。

peer 只放运行时真正加载、或公开声明真正引用的包（用户 2026-09-25 定，第二轮审查 P1-11）：wow-generator 只用 `@ahoo-wang/fetcher-openapi` 的类型，构建后不留痕迹，所以它是 devDependency；wow-react 从不导入 `@ahoo-wang/fetcher-eventstream`，那是 wow-client 的 peer，wow-react 不再声明。

TypeScript 不是 peer（用户 2026-09-25 定，第二轮审查 P1-15）：没有哪个包声明 `peerDependencies.typescript`，支持范围写在文档里。下限是 TypeScript 6；包检查（`package-check.mjs`，`package` 任务、发布预检与 `npm-smoke`）在同一个干净项目里用 TypeScript 6.0 和最新的 7.x 各编译一遍使用方代码。要抬下限，改 `package-check.mjs` 的 `TYPESCRIPT_VERSIONS` 和文档，只在 `x.Y.0` 里做。
