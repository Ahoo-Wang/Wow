# Wow Documentation Information Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the bilingual Wow documentation entry points and navigation around the confirmed user journey without moving any existing page or rewriting later content batches.

**Architecture:** Keep every existing Markdown path and use the existing VitePress navbar, sidebar, home pages, guide indexes, and onboarding indexes as the only routing layer. Chinese is the content baseline; each task updates its English mirror in the same commit and proves the pair with the existing VitePress build.

**Tech Stack:** VitePress 1.6.4, TypeScript configuration, Markdown, pnpm, Node.js, Git

**Spec:** `document/design/2026-08-27-documentation-rewrite.md`

## Global Constraints

- This plan implements only batch 1, **信息架构基础**. Do not rewrite the introduction, quickstart, core guides, references, articles, or module README content beyond links and entry-page routing required by this batch.
- Keep all existing Markdown files and public URLs. Do not rename or move pages.
- Chinese is the content baseline. English must preserve the same navigation hierarchy, routes, completion criteria, and technical meaning in the same task.
- `documentation/docs/zh/guide/introduction.md` remains the canonical value narrative; entry pages summarize it and link to it.
- Do not add dependencies, navigation generators, custom content DSLs, or CI workflows.
- Do not change Gradle modules, public APIs, OpenAPI/Schema, release, deployment, PR, or merge behavior.
- Use existing repository examples and routes. Do not create a documentation-only sample application.
- Every task must pass `pnpm --dir documentation docs:build` and `git diff --check` before its commit.
- Commit only the files listed by the current task. Never add `.superpowers/`, build output, `node_modules`, or unrelated user changes.

## File Map

| Responsibility | Chinese | English |
| --- | --- | --- |
| Primary navbar | `documentation/docs/.vitepress/configs/navbar.zh.ts` | `documentation/docs/.vitepress/configs/navbar.en.ts` |
| Section sidebars | `documentation/docs/.vitepress/configs/sidebar.zh.ts` | `documentation/docs/.vitepress/configs/sidebar.en.ts` |
| Site home routing | `documentation/docs/zh/index.md` | `documentation/docs/en/index.md` |
| Start/development map | `documentation/docs/zh/guide/index.md` | `documentation/docs/en/guide/index.md` |
| Role-based evaluation | `documentation/docs/zh/onboarding/index.md` | `documentation/docs/en/onboarding/index.md` |

No new production file is required. The VitePress configuration remains the navigation source of truth.

---

### Task 1: Replace Competing Top-Level Entry Points

**Files:**
- Modify: `documentation/docs/.vitepress/configs/navbar.zh.ts`
- Modify: `documentation/docs/.vitepress/configs/navbar.en.ts`

**Interfaces:**
- Consumes: Existing page URLs under `/guide/`, `/onboarding/`, `/articles/`, `/reference/`, and `/dokka/`.
- Produces: Six mirrored top-level entry points: Start, Development Guide, Production Operations, Reference, API, and Resources.

- [ ] **Step 1: Establish a fresh documentation baseline**

Run from the repository root:

```bash
test -x documentation/node_modules/.bin/vitepress || CI=true pnpm --dir documentation install --frozen-lockfile
pnpm --dir documentation docs:build
git status --short
```

Expected: the VitePress build exits `0`. Record any pre-existing untracked files and do not add them to later commits.

- [ ] **Step 2: Run the new-navbar contract and verify the current configuration fails it**

```bash
! rg -n "activeMatch: '\^/zh/(articles|onboarding)/'" documentation/docs/.vitepress/configs/navbar.zh.ts
! rg -n "activeMatch: '\^/(articles|onboarding)/'" documentation/docs/.vitepress/configs/navbar.en.ts
```

Expected before editing: both commands fail because the old competing top-level entries still own `activeMatch` entries.

- [ ] **Step 3: Replace the Chinese navbar with the confirmed journey**

Keep the existing imports and export type. Replace only the `navbarZh` array with this structure and exact routes:

```ts
export const navbarZh: DefaultTheme.NavItem[] = [
    {
        text: '开始使用',
        items: [
            {text: '认识 Wow', link: '/zh/guide/introduction'},
            {text: '30 分钟快速上手', link: '/zh/guide/getting-started'},
            {text: '接入现有项目', link: '/zh/guide/existing-project'},
            {text: '按角色评估与参与', link: '/zh/onboarding/'},
        ],
    },
    {text: '开发指南', link: '/zh/guide/'},
    {
        text: '生产运维',
        items: [
            {text: '生产最佳实践', link: '/zh/guide/best-practices'},
            {text: '备份、恢复与重放', link: '/zh/guide/recovery'},
            {text: '可观测性', link: '/zh/guide/advanced/observability'},
            {text: '故障排查', link: '/zh/guide/troubleshooting'},
            {text: '迁移指南', link: '/zh/guide/migration'},
        ],
    },
    {
        text: '参考',
        items: [
            {
                text: '配置',
                items: [
                    {text: '核心配置', link: '/zh/reference/config/core'},
                    {text: '基础设施', link: '/zh/reference/config/infrastructure'},
                    {text: '可观测性', link: '/zh/reference/config/observability'},
                    {text: '事件补偿', link: '/zh/reference/config/compensation'},
                ],
            },
            {
                text: '示例',
                items: [
                    {text: '订单与购物车（Kotlin）', link: '/zh/reference/example/order'},
                    {text: '银行转账（Java）', link: '/zh/reference/example/transfer'},
                    {text: '事件补偿', link: '/zh/reference/example/compensation'},
                ],
            },
        ],
    },
    {text: 'API', link: '/dokka/index.html', target: '_blank'},
    {
        text: '资源',
        items: [
            {text: '文章', link: '/zh/articles/'},
            {text: 'Agent Skills', link: '/zh/guide/skills'},
            {text: '项目模板', link: 'https://github.com/Ahoo-Wang/wow-project-template'},
            {text: '生态资源', link: '/zh/reference/ecosystem'},
            {text: '更新日志', link: 'https://github.com/Ahoo-Wang/Wow/releases'},
        ],
    },
]
```

- [ ] **Step 4: Mirror the navbar in English**

Use the same item order and routes without `/zh`:

```ts
export const navbarEn: DefaultTheme.NavItem[] = [
    {
        text: 'Start',
        items: [
            {text: 'Why Wow', link: '/guide/introduction'},
            {text: '30-Minute Quickstart', link: '/guide/getting-started'},
            {text: 'Adopt in an Existing Project', link: '/guide/existing-project'},
            {text: 'Evaluate or Contribute by Role', link: '/onboarding/'},
        ],
    },
    {text: 'Development Guide', link: '/guide/'},
    {
        text: 'Production Operations',
        items: [
            {text: 'Production Best Practices', link: '/guide/best-practices'},
            {text: 'Backup, Restore, and Replay', link: '/guide/recovery'},
            {text: 'Observability', link: '/guide/advanced/observability'},
            {text: 'Troubleshooting', link: '/guide/troubleshooting'},
            {text: 'Migration Guide', link: '/guide/migration'},
        ],
    },
    {
        text: 'Reference',
        items: [
            {
                text: 'Configuration',
                items: [
                    {text: 'Core Configuration', link: '/reference/config/core'},
                    {text: 'Infrastructure', link: '/reference/config/infrastructure'},
                    {text: 'Observability', link: '/reference/config/observability'},
                    {text: 'Compensation', link: '/reference/config/compensation'},
                ],
            },
            {
                text: 'Examples',
                items: [
                    {text: 'Order and Cart (Kotlin)', link: '/reference/example/order'},
                    {text: 'Bank Transfer (Java)', link: '/reference/example/transfer'},
                    {text: 'Event Compensation', link: '/reference/example/compensation'},
                ],
            },
        ],
    },
    {text: 'API', link: '/dokka/index.html', target: '_blank'},
    {
        text: 'Resources',
        items: [
            {text: 'Articles', link: '/articles/'},
            {text: 'Agent Skills', link: '/guide/skills'},
            {text: 'Project Template', link: 'https://github.com/Ahoo-Wang/wow-project-template'},
            {text: 'Ecosystem', link: '/reference/ecosystem'},
            {text: 'Changelog', link: 'https://github.com/Ahoo-Wang/Wow/releases'},
        ],
    },
]
```

- [ ] **Step 5: Run the navbar contract and site build**

```bash
! rg -n "activeMatch: '\^/zh/(articles|onboarding)/'" documentation/docs/.vitepress/configs/navbar.zh.ts
! rg -n "activeMatch: '\^/(articles|onboarding)/'" documentation/docs/.vitepress/configs/navbar.en.ts
rg -n "text: '(开始使用|开发指南|生产运维|参考|API|资源)'" documentation/docs/.vitepress/configs/navbar.zh.ts
rg -n "text: '(Start|Development Guide|Production Operations|Reference|API|Resources)'" documentation/docs/.vitepress/configs/navbar.en.ts
pnpm --dir documentation docs:build
git diff --check
```

Expected: the old top-level `activeMatch` entries are absent, all six new labels are present in each language, VitePress exits `0`, and `git diff --check` prints nothing.

- [ ] **Step 6: Commit the navbar change**

```bash
git add documentation/docs/.vitepress/configs/navbar.zh.ts documentation/docs/.vitepress/configs/navbar.en.ts
git commit -m "docs: reorganize primary documentation navigation"
```

---

### Task 2: Reorder the Guide Sidebar Around User Tasks

**Files:**
- Modify: `documentation/docs/.vitepress/configs/sidebar.zh.ts`
- Modify: `documentation/docs/.vitepress/configs/sidebar.en.ts`

**Interfaces:**
- Consumes: The 73 unchanged non-home pages in each locale. The current sidebar exposes only 72 because `guide/test-runtime.md` is missing.
- Produces: Mirrored sidebar groups that expose all 73 pages, including `test-runtime`, through the journey confirmed by the top-level navbar.

- [ ] **Step 1: Run the new-group contract and verify it fails**

```bash
for nav_label in 开始使用 领域开发 读模型与查询 接口与自动化 测试与交付 生产运维; do
  rg -q "text: '$nav_label'" documentation/docs/.vitepress/configs/sidebar.zh.ts || exit 1
done
for nav_label in Start 'Domain Development' 'Read Models and Queries' 'Interfaces and Automation' 'Testing and Delivery' 'Production Operations'; do
  rg -q "text: '$nav_label'" documentation/docs/.vitepress/configs/sidebar.en.ts || exit 1
done
```

Expected before editing: the command does not find the complete group set.

- [ ] **Step 2: Replace only the `/zh/guide/` groups in the Chinese sidebar**

Keep the existing `/zh/articles/`, `/zh/onboarding/`, and `/zh/reference/` route keys. Change the onboarding group title to `评估与参与` and its index label to `按角色选择`.

Under `/zh/guide/`, preserve every existing link exactly once and use this complete group map:

```text
开始使用
  index.html, introduction, getting-started, existing-project, core-concepts
领域开发
  modeling, eventstore, snapshot, command-gateway, saga, event-processor, event-compensation
读模型与查询
  projection, query, data-access
接口与自动化
  open-api, skills, bi
测试与交付
  test-suite, application-testing, test-runtime
生产运维
  configuration, bi-operations, best-practices, recovery, troubleshooting,
  migration, migration/traditional-architecture, migration/v6-to-v8,
  migration/runtime-orchestration
扩展
  kafka, mongo, redis, elasticsearch, opentelemetry, webflux, cocache,
  cosec, apiclient, spring-boot-starter, tck
深入原理
  architecture, runtime-lifecycle, aggregate-lifecycle, event-bus,
  event-evolution, serialization, data-flow, module-dependencies,
  id-generator, compiler, prepare-key, schema, metrics, observability,
  aggregate-scheduler
```

Keep the existing `base` paths: `/zh/guide/`, `/zh/guide/extensions/`, and `/zh/guide/advanced/`. Keep the migration group nested under `生产运维` so its three child links retain `migration/` paths.

- [ ] **Step 3: Apply the exact English mirror**

Use identical links without `/zh` and these group labels:

```text
Start
Domain Development
Read Models and Queries
Interfaces and Automation
Testing and Delivery
Production Operations
Extensions
How Wow Works
```

Rename the onboarding group to `Evaluate and Contribute` and its index item to `Choose by Role`. Do not translate or rename URL slugs.

- [ ] **Step 4: Verify link coverage and build**

```bash
test "$(rg -o 'link:' documentation/docs/.vitepress/configs/sidebar.zh.ts | wc -l | tr -d ' ')" = 73
test "$(rg -o 'link:' documentation/docs/.vitepress/configs/sidebar.en.ts | wc -l | tr -d ' ')" = 73
for nav_label in 开始使用 领域开发 读模型与查询 接口与自动化 测试与交付 生产运维 扩展 深入原理; do
  rg -q "text: '$nav_label'" documentation/docs/.vitepress/configs/sidebar.zh.ts || exit 1
done
for nav_label in Start 'Domain Development' 'Read Models and Queries' 'Interfaces and Automation' 'Testing and Delivery' 'Production Operations' Extensions 'How Wow Works'; do
  rg -q "text: '$nav_label'" documentation/docs/.vitepress/configs/sidebar.en.ts || exit 1
done
pnpm --dir documentation docs:build
git diff --check
```

Expected: each sidebar still contains 73 links, all eight guide groups exist, the site builds, and no whitespace errors are reported.

- [ ] **Step 5: Commit the sidebar change**

```bash
git add documentation/docs/.vitepress/configs/sidebar.zh.ts documentation/docs/.vitepress/configs/sidebar.en.ts
git commit -m "docs: organize guides around user tasks"
```

---

### Task 3: Rewrite the Journey Entry Pages

**Files:**
- Modify: `documentation/docs/zh/index.md`
- Modify: `documentation/docs/en/index.md`
- Modify: `documentation/docs/zh/guide/index.md`
- Modify: `documentation/docs/en/guide/index.md`
- Modify: `documentation/docs/zh/onboarding/index.md`
- Modify: `documentation/docs/en/onboarding/index.md`

**Interfaces:**
- Consumes: The navbar routes from Task 1 and sidebar grouping from Task 2.
- Produces: A direct home-to-quickstart route, one journey map for developers, and one role-based evaluation entry.

- [ ] **Step 1: Run the new-entry contract and verify it fails**

```bash
rg -n "30 分钟首次成功|从价值到首次成功|评估与参与" documentation/docs/zh/index.md documentation/docs/zh/guide/index.md documentation/docs/zh/onboarding/index.md
rg -n "First Success in 30 Minutes|From Value to First Success|Evaluate and Contribute" documentation/docs/en/index.md documentation/docs/en/guide/index.md documentation/docs/en/onboarding/index.md
```

Expected before editing: the complete phrase set is absent.

- [ ] **Step 2: Make the home-page primary action the 30-minute path**

In both home-page frontmatters, keep the current logo, award metadata, feature cards, and value copy unchanged. Replace only the hero actions with this semantic order:

```text
Chinese: 30 分钟快速上手 → 认识 Wow → 开发指南 → GitHub
English: 30-Minute Quickstart → Why Wow → Development Guide → GitHub
```

Use existing routes:

```text
/zh/guide/getting-started  | /guide/getting-started
/zh/guide/introduction     | /guide/introduction
/zh/guide/                 | /guide/
https://github.com/Ahoo-Wang/Wow
```

Add one short Markdown section after the frontmatter with the heading `## 从价值到首次成功` / `## From Value to First Success`. It must state that the Introduction explains fit and cost, while the quickstart proves command → event → sourced state. Do not duplicate the Introduction's six-part argument.

Use this exact copy:

```markdown
## 从价值到首次成功

先读[简介](./guide/introduction.md)，判断 Wow 的价值、适用边界和采用成本；准备动手时，进入[30 分钟快速上手](./guide/getting-started.md)。领域测试、真实 HTTP 命令和版本化事件溯源状态均验证通过，才算完成第一次成功。
```

```markdown
## From Value to First Success

Read the [Introduction](./guide/introduction.md) to evaluate Wow's value, fit, and adoption cost. When you are ready to build, follow the [30-Minute Quickstart](./guide/getting-started.md). Your first success requires a passing domain test, a real HTTP command, and verified versioned event-sourced state.
```

- [ ] **Step 3: Rewrite the guide indexes as the journey map**

Use this exact section order in both languages:

```text
1. First Success in 30 Minutes / 30 分钟首次成功
2. Continue Building / 继续构建
3. Prepare for Production / 准备生产运行
4. Look Up Exact Facts / 精确查阅
5. Evaluate or Contribute by Role / 按角色评估或参与
```

The first section must list the confirmed completion gates in order:

```text
create from wow-project-template
confirm the selected Wow version
pass the domain test
start the server
send a real HTTP command and inspect the command result
load versioned sourced state
```

Keep every current task-matrix row after these five sections and rename the matrix `按任务继续` / `Continue by Task`. Delete the old `三条建议路径` / `Three Suggested Paths` section because the new five sections replace it.

- [ ] **Step 4: Rewrite onboarding indexes as role-based evaluation pages**

Use the title and H1 `评估与参与` / `Evaluate and Contribute`. Preserve the four existing role pages and their current routes:

```text
Contributor
Staff Engineer
Executive
Product Manager
```

Keep a concise role table with: reader, decision they need to make, first page, and completion signal. Remove hard-coded project, Kotlin, Spring Boot, and Gradle version prose from the index; link to repository configuration only where a role guide needs exact facts.

Use these exact row meanings in both languages:

```text
Contributor | Can I build and verify a reviewable Wow change? | contributor-guide | Local checks pass and the change has a clear review boundary
Staff Engineer | Does Wow fit our architecture and operating model? | staff-engineer-guide | Benefits, invariants, dependencies, migration, and recovery responsibilities are explicit
Executive | Is adoption justified by value, cost, and delivery risk? | executive-guide | The decision names expected value, ownership, evidence, and stop conditions
Product Manager | Can domain behavior become testable acceptance criteria? | product-manager-guide | Commands, events, completion semantics, and failure outcomes are explicit
```

End with one prioritized next step:

```text
Developers → guide/getting-started
Architecture or operations evaluators → guide/introduction
Contributors → onboarding/contributor-guide
```

- [ ] **Step 5: Verify entry wording, links, and the full site**

```bash
rg -n "30 分钟首次成功|从价值到首次成功|评估与参与" documentation/docs/zh/index.md documentation/docs/zh/guide/index.md documentation/docs/zh/onboarding/index.md
rg -n "First Success in 30 Minutes|From Value to First Success|Evaluate and Contribute" documentation/docs/en/index.md documentation/docs/en/guide/index.md documentation/docs/en/onboarding/index.md
pnpm --dir documentation docs:build
git diff --check
```

Expected: all phrases are present, VitePress reports no broken internal links, and both commands exit `0`.

- [ ] **Step 6: Commit the entry-page change**

```bash
git add documentation/docs/zh/index.md documentation/docs/en/index.md documentation/docs/zh/guide/index.md documentation/docs/en/guide/index.md documentation/docs/zh/onboarding/index.md documentation/docs/en/onboarding/index.md
git commit -m "docs: create a first-success documentation journey"
```

---

### Task 4: Browser QA and Batch Acceptance

**Files:**
- Verify only: all files modified in Tasks 1-3

**Interfaces:**
- Consumes: The completed navbar, sidebars, and six entry pages.
- Produces: Evidence that the information architecture works in both rendered locales before batch 2 begins.

- [ ] **Step 1: Build from the final Task 3 commit**

```bash
pnpm --dir documentation docs:build
git diff --check HEAD~3..HEAD
```

Expected: both commands exit `0`.

- [ ] **Step 2: Start the built-site preview**

```bash
pnpm --dir documentation docs:preview
```

Keep the process running and use the exact local URL printed by VitePress.

- [ ] **Step 3: Verify the English journey in a browser**

Open and inspect these routes:

```text
/
/guide/
/onboarding/
/reference/config/core
/articles/
```

Confirm:

```text
Start, Development Guide, Production Operations, Reference, API, Resources appear in that order.
Articles and Onboarding no longer appear as competing top-level entries.
The home primary action opens /guide/getting-started.
The guide sidebar exposes the eight approved groups.
The role entry remains reachable from Start.
Reference and Articles remain reachable without changing their URLs.
```

- [ ] **Step 4: Verify the Chinese mirror in a browser**

Open and inspect:

```text
/zh/
/zh/guide/
/zh/onboarding/
/zh/reference/config/core
/zh/articles/
```

Confirm the Chinese hierarchy, route order, completion gates, and next steps match the English version semantically. Use the locale switch from at least one guide page and confirm it opens the counterpart rather than a 404 page.

- [ ] **Step 5: Run the final repository checks**

Stop the preview process, then run:

```bash
pnpm --dir documentation docs:build
git diff --check
git status --short --branch
git log --oneline -4
```

Expected:

```text
VitePress exits 0.
git diff --check prints nothing.
The three planned task commits are the only implementation commits after the approved design and plan, except for the explicitly named QA correction commit when browser QA finds a defect.
No build output, node_modules, or .superpowers files are staged or committed.
```

If browser QA finds a defect, make the smallest correction only in the files listed by Tasks 1-3, rerun the owning task's checks, then record the correction separately:

```bash
git add -- documentation/docs/.vitepress/configs/navbar.zh.ts documentation/docs/.vitepress/configs/navbar.en.ts documentation/docs/.vitepress/configs/sidebar.zh.ts documentation/docs/.vitepress/configs/sidebar.en.ts documentation/docs/zh/index.md documentation/docs/en/index.md documentation/docs/zh/guide/index.md documentation/docs/en/guide/index.md documentation/docs/zh/onboarding/index.md documentation/docs/en/onboarding/index.md
git commit -m "docs: fix documentation journey QA findings"
```
