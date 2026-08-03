# Wow Agent Skills

本目录提供四个以用户主要交付结果划分的 Wow Agent Skills。每次任务只选择一个 Primary Skill，由它负责从取证到完成验证，不在执行过程中切换到其他 Wow Skill。

这些 Skills 的目标不是复制框架文档或维护 API 百科，而是提供当前源码无法直接表达的工作流、架构不变量、安全边界和完成证据。所有具体 API、配置、默认值、模块名和生成契约都必须在目标 checkout 或精确目标 tag 中重新确认。

## Skills

| Skill | Primary outcome | Boundary |
|---|---|---|
| `wow-develop` | 设计、实现、测试、重构或解释 Wow 行为 | 不用于已有 diff 的审查、已有故障的诊断或需要数据切换/回滚的迁移 |
| `wow-review` | 输出 findings、质量判断、合并准备度，或完成 review-and-fix | 不用于症状驱动的根因诊断或迁移专项审查 |
| `wow-debug` | 复现、定位并解释已有故障，或完成 diagnose-and-fix | 不用于主动功能开发或普通 diff review |
| `wow-migrate` | 完成跨主版本或存储/数据格式迁移 | 不用于首次采用 Wow、无数据迁移的常规 v8 升级或普通故障 |

## Selection order

按主要交付结果选择，不按涉及的组件名选择：

1. 现有 v6→v8 兼容，或任意版本起点的存储格式、数据切换、对账、回滚是主问题：`wow-migrate`。
2. 存在失败、hang、错误状态或可复现症状，目标是根因：`wow-debug`。
3. 目标是 findings、批准或合并准备度：`wow-review`。
4. 目标是设计、修改、测试或解释 Wow：`wow-develop`。
5. 与 Wow 行为或 API 无直接关系：不激活本包。

`review-and-fix` 始终由 `wow-review` 完成；`diagnose-and-fix` 始终由 `wow-debug` 完成；不要在任务中转入另一个 Wow Skill。

## Content model

- `SKILL.md` 只保存入口契约、执行顺序、授权边界和 reference 选择规则。
- `references/` 保存稳定决策规则、源码发现方法和风险边界，不保存易漂移的 API 清单。
- `assets/` 保存可复制到输出中的模板，不作为推理资料默认加载。
- `scripts/` 只保存重复、确定且容易手写出错的操作；当前仅迁移静态审计适合脚本化。
- 每个 Skill 的 `evals/` 保存黑盒 activation/behavior 用例；它们不由运行时 Skill 主动加载。
- 仓库根目录的 `scripts/validate_wow_skills.py` 与 `scripts/run_wow_skill_evals.py` 是稳定的维护 CLI，不进入插件运行时资源；实现分别位于 `scripts/wow_skill_validator/` 和 `scripts/wow_skill_runner/`，新增规则应进入职责对应的内部模块。
- 不维护通用 Markdown、HTML、自然语言或代码风格 parser。

## Validation

运行自包含的结构与契约校验以及回归测试：

```bash
python3 scripts/validate_wow_skills.py
python3 -m unittest scripts/test_validate_wow_skills.py scripts/test_run_wow_skill_evals.py
```

校验器执行以下确定性检查：

- 在仓库内校验标准 Skill name/description 约束，不依赖用户目录中的 `quick_validate.py`；
- 校验 `agents/openai.yaml`、`plugins.json`、本地 Markdown 链接、资源 containment 和 shell 语法；
- shell 校验只执行 `bash -n`，不会调用待校验脚本的 `--help` 或其他运行时入口；
- 校验 activation schema、behavior v2 contract、Skill 覆盖和冲突覆盖；
- 拒绝绝对路径、`..`、symlink、hardlink、非 patch setup 和越界写入 allowlist。

结构校验只能证明包结构与静态 contract 约束成立，不能证明 eval 可执行、自动激活或行为正确。使用仓库 runner 准备隔离 fixture：

```bash
run_dir="$(mktemp -d)"
python3 scripts/run_wow_skill_evals.py prepare \
  --case B05-review-fix-order \
  --subject "$(git rev-parse HEAD)" \
  --output "$run_dir" \
  --adapter-key /protected/path/to/adapter-key.json
```

`prepare` 冻结 contract、完整基线 manifest 和去除 `evals/` 的 runtime-only plugin copy，只把原始 prompt、精确 subject/base/baseline、临时 workspace、读写 policy 和冻结 trace schema 交给 adapter。RUN v2 request 同时封印 case/suite/sourceRepo/workspace/revision 以及 contract/plugin/baseline hashes；`prepare` 使用受保护 key 对完整 RUN 描述符做 domain-separated HMAC 封印，受信 evidence 再独立签署 `requestSha256`。`requestSha256` 是磁盘上 `request.json` UTF-8 原始文件字节（包括格式化空白与结尾换行）的 SHA-256，`prepare` 的 JSON 输出也会返回该值；它不是 canonical JSON hash。`verify` 只从已封印的冻结 request、contract、plugin 与 baseline 重建可信上下文，不回读可能已演进的实时 source package，因此可改写的 `run.json` 不能自行重签 contract、重建 baseline 或把 workspace 重定向到另一目录，同时异步执行不受后续 skills 更新影响。workspace policy 还必须阻断对源 checkout 中 eval contract、setup 和 oracle 的读取。B05 的 review base 固定为 setup 前的 subject，避免把调用者分支中的其他 diff 混入 known-defect case；B04 使用显式 `--base`，由 runner 以独立 pack 传输精确 subject/base 的 object closure，再移除 remote，以没有 external object/remote 依赖的 standalone Git clone 评估真实分支三点 diff。未被 ref 引用的 detached commit 也不依赖 clone 的 advertised refs。

客户端 adapter 必须启动全新任务、原样发送 prompt、执行 policy，并按 [`wow-skill-eval-trace.schema.json`](../scripts/schemas/wow-skill-eval-trace.schema.json) 写出 v2 activation/tool trace。每个 command event 都绑定 fixture 根目录 cwd、原始 argv 与解析后的绝对 executable；adapter 还要签署受控环境与 direct-execution policy。activation case 必须在记录路由后立即终止，不能继续执行原始 prompt 中的写入任务。evidence 还必须由受保护、绑定 adapter 名称与版本的 HMAC key 签名；key 不能暴露给 Agent 或放入 fixture/plugin/workspace。没有受信 key 时只能得到 `UNSUPPORTED`。之后执行：

```json
{"schemaVersion":1,"keyId":"adapter-key-id","adapterName":"pinned-adapter","adapterVersion":"1.0.0","secretHex":"<at-least-32-random-bytes-as-hex>"}
```

key 文件必须只有 owner 可读写；真实 adapter 在受保护通道中签署除 `attestation` 外的 canonical evidence JSON。

```bash
python3 scripts/run_wow_skill_evals.py verify \
  --run-dir "$run_dir" \
  --evidence /absolute/path/to/evidence.json \
  --adapter-key /protected/path/to/adapter-key.json
python3 scripts/run_wow_skill_evals.py cleanup \
  --run-dir "$run_dir" \
  --adapter-key /protected/path/to/adapter-key.json
```

正常 cleanup 会先验证 RUN 封印，按冻结 fixture kind 决定是否查询真实 Git worktree 注册，并重签 `cleaned` 生命周期状态；重复 cleanup 不会删除后来复用的同名目录。若 marker 已损坏，默认 fail closed；只有同时保有受保护 key、显式指定可信 source repo 的操作者才能恢复固定的 runner-owned 路径：`cleanup --run-dir "$run_dir" --adapter-key ... --force-recovery --source-repo "$(git rev-parse --show-toplevel)"`。recovery 会写入由同一 key 签署且绑定 run/source 的 tombstone，重试不会删除后来复用的目录；该路径不承诺处理 key 同时丢失的场景。

runner 独立解析并记录 subject/base commit、setup/content SHA-256、ephemeral baseline，并以逐路径 kind/mode/content manifest 比较最终 workspace；因此空目录、ignored、`assume-unchanged` 和 `skip-worktree` 不能隐藏越界修改，FIFO/socket/device 等特殊文件会直接 fail closed，供内容断言使用的完整 diff 也显式纳入未暂存及 ignored 的新增产物。显式声明的 Gradle/Kotlin tool-output 目录不计作 Agent 产物，但 `init.gradle*`、`init.d/`、tool-home `gradle.properties` 等控制文件始终禁止；adapter 必须把可写 cache/tmp 重定向到 workspace 内的 `.eval-runtime/`，并清除会改变命令语义的环境变量。mutating case 仍必须满足受限源路径、根目录中的精确 executable/argv、真实 RED → write → GREEN command trace、目标产物和成功进程结果；read-only case 不得改变 baseline source tree。Cart 边界由 runner 在 Agent 完成后临时注入的隐藏 JUnit 双分支测试验证；迁移 fixture 另以严格平台契约和 synthetic data 的中断恢复、幂等、checksum、全量对账 oracle 验证，其中 interrupted gate 以不可预测的 JSON 无害字节前缀封印排除从零重写。最终回答中的“已测试”不能代替这些证据。

`sandbox.noExternalMutation` 要求阻断 workspace 外写入、网络 mutation、connector 和 eval 内容访问；`sandbox.noExternalRead` 另行要求阻断外部业务数据读取。需要 Gradle 的 mutating case 必须让受信 adapter 按 policy 执行 RED/GREEN 命令并填充 workspace-local `.eval-runtime/gradle-home`；隐藏 Cart oracle 只以 `--offline` 复用该 cache 并强制重新执行任务，缺少 wrapper、dependency cache 或 Java toolchain 时返回 `UNSUPPORTED`，不能误报 assertion `FAIL`。该 cache 不能被解释成生产配置、日志或数据读取授权。缺少签名、真实 activation/tool trace 或 enforcement capability 时同样返回 `UNSUPPORTED`；证据畸形/篡改返回 `ERROR`；assertion 不满足返回 `FAIL` 且 CLI 非零退出。结构校验、Hub 同步或“回答看起来正确”都不得升级为 behavior `PASS`。

## Distribution

`plugins.json` 显式列出可分发的四个 Skill。Ahoo Skills Hub 负责同步、生成和验证插件产物；Wow 仓库拥有并维护 Skill 内容。本次架构是有意的破坏性重写，不分发旧名称或兼容别名；发布后，既有安装必须刷新或重新安装插件，再确认四个 Skill 均可发现。
