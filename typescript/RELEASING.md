# 发布手册

Maven 和 npm 用同一个版本号、同一个 `v*` tag 一起发布（见 [MIGRATION.md](MIGRATION.md)「发布策略」）。本文是维护者照做的手册：发布流水线怎么把关、首发怎么做、日常发版和出错时怎么处理。

**下一个版本是 9.2.0。** `v9.1.5` 以来有带 `!` 的提交（#3298，只改了还没发布的 view-engine），发版准入会拒绝 9.1.6。9.2.0 本来也适合做首发：它新增了一条 npm 产品线。

## 发布流水线

`package-deploy.yml` 在创建 GitHub release 时运行，也可以手动在一个 `v*` tag 上运行（Actions → Packages Deploy → Run workflow → Use workflow from → Tags → `v<version>`）。别的 ref 一律拒绝。所有 action 都按 commit SHA 锁定，Renovate 的 `github-actions` 组连同版本注释一起升级。

| Job              | 做什么                                                                                                                                                                                                                       | 权限                                               |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| `admission`      | ref 必须是 `v*` tag；tag = `gradle.properties` = 各 `package.json` 的版本；发版准入（`.github/scripts/release-admission.mjs`）                                                                                               | `contents: read`、`actions: write`，不接触任何密钥 |
| `preflight`      | `pnpm build:typescript` → 打出 npm tarball → 包检查（`.github/scripts/package-check.mjs`）→ `publish-npm.mjs --dry-run` → 上传 tarball；`./gradlew build allIntegrationTest`、`publishToMavenLocal`（签名）→ 上传 Maven 产物 | `contents: read`                                   |
| `github-deploy`  | 发布到 GitHub Packages                                                                                                                                                                                                       | `packages: write`                                  |
| `central-deploy` | 发布到 Maven Central                                                                                                                                                                                                         | Sonatype 凭据                                      |
| `npm-deploy`     | 在 environment `npm-publish` 里等维护者审批，然后发布 preflight 检查过的**那几个 tarball**：不安装、不构建，npm 固定为精确版本，OIDC 可信发布并带 provenance                                                                 | `id-token: write`，environment `npm-publish`       |

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

### A. 代码侧（合并到 main）

- [x] 发布工作流按 SHA 锁定 action；`npm-deploy` 使用 environment `npm-publish`；手动运行只接受 `v*` tag；`concurrency` 和超时（R3-01、R3-02、R3-14）。
- [x] 发版准入：三条 TypeScript 流水线在发版提交上的完整运行，缺了就自动触发（R3-03）。
- [x] 包检查：publint、node16/nodenext/bundler 下的类型、干净项目里安装导入并运行 bin，在 `typescript.yml` 的 `package` job 和 preflight 里都跑（R3-04）；preflight 打包、检查，`npm-deploy` 只发布这些 tarball（R3-12）。
- [x] `engines.node` 统一为 `>=22.12.0`（R3-05）。
- [x] peer 范围放进具名 catalog `peers`，Renovate 只放宽、不抬下限（R3-06）。
- [x] `publish-npm.mjs` 在真实发布时拒绝不干净的工作区，以及不是 `v<version>` 那个提交的 HEAD（R3-07）。
- [x] `require` 条件有自己的 `.d.cts`；generator 的声明文件相对导入带扩展名；wow-react 只出 ESM 并有 `default` 条件（R3-09、R3-10、R3-11）。
- [ ] 元数据与文案：generator 的 description 和 keywords、wow-client 的 keywords、wow-react 的中文 README、homepage 指向文档站（R3-25）。
- [x] 文档（R4）：兼容性矩阵、快速开始、错误处理、认证、SSR/Node、CI 重新生成、排障；包 README 写「随 Wow 9.2.0 发布」，站点与 README 的 TypeScript 样例由 `documentation/test/typescript-samples.test.mjs` 对构建产物做类型检查。
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

### D. 配置 Trusted Publisher（三个包各一次）

1. npmjs.com → 包 → Settings → Trusted Publisher → GitHub Actions：
   - Organization or user：`Ahoo-Wang`
   - Repository：`Wow`
   - Workflow filename：`package-deploy.yml`
   - Environment name：`npm-publish`
2. 同一页 Publishing access 选 **Require two-factor authentication and disallow tokens**。之后只能通过 OIDC，或者本人加 2FA 发布。

### E. CI 发布 `9.2.0`

1. 发版 PR `chore(release): 9.2.0`：`pnpm set-version 9.2.0`，更新版本表与文档，合并。
2. GitHub → Releases → Draft a new release：tag `v9.2.0`（在 main 上新建），release notes **手写**，或者用 `git log --first-parent v9.1.5..v9.2.0` 整理：`v9.1.5..HEAD` 里有约 1370 个从 fetcher 导入的提交，自动生成的说明会被淹没（R3-29）。单列「Breaking」一节，写明 #3298 只影响还没发布的 view-engine。发布 release。
3. `admission` 触发三条完整运行并等待，`preflight` 跑完后 `github-deploy`、`central-deploy` 直接开始，`npm-deploy` 等审批：确认 Maven Central 那一路成功以后再在运行页面 Review deployments → `npm-publish` → Approve。
4. 核对：npmjs.com 上三个包的 9.2.0 显示 Provenance 徽章；`npm dist-tag ls @ahoo-wang/wow-client` 显示 `latest: 9.2.0`、`next: 9.2.0-rc.0`。
5. 在运行页面重跑 `npm-deploy`（Re-run failed jobs 或 Re-run job），三个包都应输出「already on npm; skipped」，验证幂等。

### F. 发布以后

1. 在 [MIGRATION.md](MIGRATION.md)「进度」里记下首发。
2. 翻转文档状态：`documentation/docs/{en,zh}/guide/typescript/` 的 `index.md`、`compatibility.md`「发布状态」、`quick-start.md` 的提示框和 `troubleshooting.md` 的 `E404` 一行，以及 `reference/typescript/index.md`，把「尚未上 npm／not yet on npm」改成已发布；包 README 已冻结在 tarball 里，只写了「随 Wow 9.2.0 发布」，不用改。然后在一个空目录里照[快速开始](../documentation/docs/zh/guide/typescript/quick-start.md)从 npm 安装、生成、编译一遍，确认页面上的安装命令能用。
3. 按 MIGRATION「下一步」：wow-project-template 切到新包以后，再对 `fetcher-wow`、`fetcher-generator` 执行 `npm deprecate`（对外操作，先问用户）。

## 日常发版

1. 发版 PR：`pnpm set-version <version>`，更新版本表、文档和快照，合并。
2. 创建 GitHub release（tag `v<version>`，指向 main 或 `release-x.y` 上的提交）。
3. 等 `admission`、`preflight`；Maven 两路成功以后批准 `npm-deploy`。

维护线 `release-x.y` 没有 push 触发，不影响发版：准入在 tag 上触发完整运行。给老版本线发补丁时，dist-tag 自动是 `release-x.y`，不会动 `latest`。

## 出错时

- **准入失败**：日志里写了是哪条流水线、哪次运行。修好以后在那次运行上 Re-run failed jobs，再重跑 `admission`。
- **只有一路失败**（Maven 与 npm 目前并行，R3-13 未定）：只重跑失败的 job，不要重跑整个工作流。npm 这一路会跳过已经发布的包。`central-deploy` 的 `closeAndReleaseSonatypeStagingRepository` 不幂等：它失败时先到 Sonatype 查看 staging 仓库的状态，已经 release 的不要再发，只剩 staging 的在网页上手动 close/release 或 drop 后重跑。
- **npm 发布以后发现问题**：npm 发布无法撤回（72 小时后连 unpublish 都不行）。发一个新的补丁版本，必要时 `npm deprecate <pkg>@<version> "<原因>"`。

## peer 范围

发布包的 `peerDependencies` 引用 `catalog:peers`（`pnpm-workspace.yaml` 里的具名 catalog），开发依赖引用默认 catalog。Renovate 对 `peers` 只做 `widen`，而且要在 Dependency Dashboard 里批准：新大版本出来时加进范围，永远不抬高下限。要抬下限，手工改 `peers`，而且只在 `x.Y.0` 里做。包检查会拒绝不从 `catalog:peers` 或 `workspace:` 取 peer 的发布包。
