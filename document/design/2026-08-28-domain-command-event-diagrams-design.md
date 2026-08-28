# 领域模型、命令、事件与协作文档图表设计

## 背景

“领域模型、命令、事件与协作”信息架构已经形成中英文各 20 个职责唯一的页面，但当前只有 6 个页面包含 Mermaid 图。其余 14 个页面主要依赖段落、表格和代码，读者必须先读完较长正文，才能建立组件关系、选择路径或状态转换的整体认知。

查询文档已经在独立分支采用“页面主图 + 精确正文”的方式补充 Mermaid 图。本设计为另外三个区域建立相同的阅读体验，但不修改查询模块，也不引入新的视觉资产系统。

## 已确认的决策

- 覆盖中英文全部 20 个领域模型、命令、事件协作页面；每页恰好一张 Mermaid 主图。
- 14 个纯文字页面新增主图。
- 现有 6 张图中：重做与事件溯源图重复的快照图，扩充过于简略的事件分发图；其余 4 张保留。
- 全部图使用内嵌 Mermaid，不新增 SVG、PNG、绘图依赖、生成脚本或主题配置。
- 中英文图使用相同图类型、节点 ID、边和分组，只本地化显示标签。
- 图是正文导航和关系摘要，不替代精确契约、错误边界、表格、代码或测试入口。
- 不修改查询文档；查询图表由独立的 `docs/query-diagrams` 工作处理。

## 范围

### 包含

- `documentation/docs/{zh,en}/guide/domain/` 的 6 个页面；
- `documentation/docs/{zh,en}/guide/command/` 的 9 个页面；
- `documentation/docs/{zh,en}/guide/event/` 的 5 个页面；
- 新增 14 组双语 Mermaid 图；
- 重构 `domain/snapshot` 与 `event/dispatch` 的双语现有图；
- Mermaid 语法构建、双语结构、页面覆盖率和代表性桌面/窄屏视觉检查。

### 不包含

- `guide/query`、`guide/query/**`、`projection` 或 `data-access`；
- 页面信息架构、URL、sidebar、章节职责或技术结论重写；
- VitePress 主题、Mermaid 插件配置、颜色主题或全局 CSS；
- 图片资产、图标库、截图、动画或交互图；
- 框架源码、API、Schema、配置、示例行为、依赖、CI/CD 或发布流程。

## 视觉语言

### 图型选择

- `flowchart LR`：线性调用、入口拓扑、处理链和左右对照；
- `flowchart TB`：选择分支、层级关系和移动端更易阅读的复杂拓扑；
- `sequenceDiagram`：跨组件调用、请求与响应、远程回调；
- `stateDiagram-v2`：具有明确状态与转换条件的生命周期。

不使用 Mermaid `mindmap`、自定义主题、颜色类或实验性语法，避免构建与主题兼容风险。

### 复杂度限制

- 一张图只回答一个核心问题；
- 普通图通常不超过 8 个节点；
- 运行时原理图最多 12 个节点或参与者；
- 超过限制时通过 `subgraph` 分组、改变方向或删去非关键细节解决，不拆成第二张主图；
- 节点标签使用短语，不把段落、完整签名、配置值或异常说明塞入图中。

### 放置与正文关系

- 图紧邻其核心概念第一次出现的位置，而不是机械地堆在 frontmatter 之后；
- 图前用一句话说明“这张图回答什么问题”；
- 图后正文继续解释保证、例外和失败边界；
- 不删除正文中的关键表格或技术说明；只有已被图完整替代的纯文本 ASCII 流程可删除；
- 不依赖颜色表达状态或选择，所有语义都必须出现在节点、边标签或正文中。

### Mermaid 编写规则

- 中英文对应图共享稳定的英文节点 ID，例如 `Gateway`、`Dispatcher`、`Store`；
- 显示标签分别使用自然中文和英文；
- 包含泛型、尖括号或特殊字符的标签必须加引号并使用 HTML 转义；
- 边标签只用于真正的条件、结果或协议边界；
- 避免一条边同时表达顺序、失败和保证三种含义。

## 逐页图表职责

### 领域模型

| 页面 | 状态 | 图型 | 主图唯一职责 |
| --- | --- | --- | --- |
| `domain/index` | 新增 | `flowchart TB` | 展示聚合边界如何连接事件溯源、事件演进、快照、生命周期与命令定义阅读入口。 |
| `domain/aggregate` | 新增 | `flowchart TB` | 展示限界上下文、聚合边界、状态、命令处理、领域事件和不变量的关系。 |
| `domain/event-sourcing` | 保留 | `flowchart LR` | 展示最新/历史版本恢复如何选择快照或空聚合，并从 `expectedNextVersion` 顺序溯源。 |
| `domain/event-evolution` | 新增 | `flowchart LR` | 展示 `EventUpgraderFactory` 按 `@Order` 对每条记录恰好调用每个已注册 Upgrader 一次；每步可返回原样、升级或 DroppedEvent 记录，并验证最终记录可解析。 |
| `domain/snapshot` | 重做 | `flowchart TB` | 对照完整事件回放与“快照 + 尾部事件”两条恢复路径，并汇聚到相同聚合状态。 |
| `domain/lifecycle` | 新增 | `stateDiagram-v2` | 展示未初始化、活动、删除和恢复状态及其合法转换，不复制完整命令管线。 |

`domain/event-sourcing` 继续拥有权威历史与恢复主流程；`domain/snapshot` 只拥有恢复优化对照，避免维护两张几乎相同的恢复图。

### 命令

| 页面 | 状态 | 图型 | 主图唯一职责 |
| --- | --- | --- | --- |
| `command/index` | 新增 | `flowchart LR` | 展示意图从命令定义、发送、处理、事件追加到可观察完成与下游协作的全局路径。 |
| `command/definition` | 新增 | `flowchart LR` | 展示命令载荷与元数据进入 Handler 后返回领域事件或 Void 的决策边界。 |
| `command/sending` | 新增 | `flowchart TB` | 对照应用内 Gateway、聚合 HTTP 路由和全局命令门面三种入口。 |
| `command/api-client` | 新增 | `sequenceDiagram` | 展示调用方、服务定位、CoApi、`/wow/command/send` 与最终结果之间的远程边界。 |
| `command/completion` | 保留 | `flowchart LR` | 展示 `PROCESSED` 之后 SNAPSHOT、PROJECTED、EVENT_HANDLED、SAGA_HANDLED 是独立分支。 |
| `command/reliability` | 新增 | `flowchart TB` | 展示失败或超时后如何查询权威结果、复用稳定 `requestId`、决定重试或停止。 |
| `command/internals/pipeline` | 保留 | `flowchart LR` | 展示 Gateway 到 EventStore、Domain/State EventBus 与 PROCESSED 的内部顺序。 |
| `command/internals/wait-runtime` | 新增 | `sequenceDiagram` | 展示 Wait Header、Coordinator、WaitState、Notifier 与 Handle 的注册、信号和清理闭环。 |
| `command/internals/transport` | 新增 | `flowchart TB` | 对照 InMemory、Kafka、Redis、LocalFirst 的写入/准入位置与 `SENT` 证据边界。 |

命令区的图按三个层级分工：`index` 是生命周期导航，`sending` 是入口选择，`internals/pipeline` 是运行时组件顺序。三者不能各自维护同一份完整调用链。

### 事件与协作

| 页面 | 状态 | 图型 | 主图唯一职责 |
| --- | --- | --- | --- |
| `event/index` | 新增 | `flowchart TB` | 根据普通副作用、跨聚合命令和持久失败恢复选择 Processor、Saga 或 Compensation。 |
| `event/processor` | 新增 | `flowchart LR` | 展示事件匹配、Filter、响应式副作用、完成通知与失败入口。 |
| `event/saga` | 新增 | `sequenceDiagram` | 展示源聚合事件触发 Saga，并顺序生成 0..N 条命令发送到目标聚合。 |
| `event/compensation` | 保留 | `stateDiagram-v2` | 展示 `FAILED → PREPARED → FAILED/SUCCEEDED` 的持久恢复状态机。 |
| `event/dispatch` | 扩充 | `flowchart TB` | 展示 Domain/State EventBus、Dispatcher、Notifier、Compensation/Retryable 与函数调用的相对位置。 |

`event/index` 只负责选择；`event/processor` 与 `event/saga` 负责使用；`event/dispatch` 负责内部 Filter 顺序。补偿状态机只在 `event/compensation` 维护。

## 技术事实边界

- 图中所有顺序、状态、分支和保证必须与页面当前正文及其引用的源码/测试一致；
- 不把 `PROCESSED` 画成所有下游阶段完成；
- 不把 `SNAPSHOT` 无条件画成已写入新快照；
- 不把 API Client 画成本地 Gateway 或完整 SSE 协议的等价实现；
- 不把 Saga 业务补偿与事件函数失败后的持久补偿合并；
- 不把 StateEvent 发布失败画成必然使命令失败；
- 不把传输 `SENT` 画成命令已处理；
- 不从旧图或旧文档继承未经当前源码、测试或已批准正文支持的承诺。

## 双语一致性

- 中英文每个对应页面必须拥有相同数量的 Mermaid 块；最终均为 1；
- 对应图使用相同图型、方向、节点 ID、边、条件与 `subgraph`；
- 只翻译显示标签和说明句，不翻译类名、阶段名、路由、Header、方法名和配置键；
- 中文先作为事实基线，英文使用自然表达，不能靠逐词翻译改变技术语义；
- 双语任一侧 Mermaid 解析失败、缺节点或边结构不同都阻塞验收。

## 可读性与可访问性

- 图不是唯一信息来源；页面正文必须能在 Mermaid 未渲染时继续表达完整契约；
- 不使用颜色、线型粗细或空间位置作为唯一语义；
- 节点和参与者名称必须可直接朗读并与正文术语一致；
- 复杂图优先改为纵向或分组，而不是缩小字体或塞入长标签；
- 页面在桌面和窄屏下都不能依赖横向拖动才能理解关键主路径。

## 实施边界

预计修改 32 个 Markdown 文件：

- 28 个文件：14 个纯文字页面的中英文主图；
- 4 个文件：双语 `domain/snapshot` 与 `event/dispatch` 现有图重构。

以下 8 个文件中的 4 组现有图默认不改：

- `domain/event-sourcing`；
- `command/completion`；
- `command/internals/pipeline`；
- `event/compensation`。

只有在实施验证发现双语结构不一致、Mermaid 语法失效或与当前正文冲突时，才允许对上述图做最小修正，并在提交说明中单独列出。

## 验收标准

### 结构

- 中英文 20 个目标页面每页恰好包含 1 个 `mermaid` fenced block；
- 20 组双语图的图型、方向、节点 ID、边和分组一致；
- 不修改查询页面、sidebar、主题配置或图片目录；
- 不新增依赖、脚本、生成文件或构建配置。

### 内容

- 每张图能用一句话说明唯一问题；
- 不存在同一区域内重复维护的完整命令链、恢复链或补偿链；
- 快照图与事件溯源图职责不同；
- 命令概览、发送和内部管线图职责不同；
- 事件选择、Processor、Saga、补偿和分发图职责不同；
- 图中阶段、失败和协议边界与当前正文及源码一致。

### 验证

至少执行：

```bash
pnpm --dir documentation docs:build
git diff --check
```

并完成以下无新增依赖检查：

- 统计中英文 20 个目标页面的 Mermaid 块，确保每页恰好 1 个；
- 比较双语页面路径集合和 Mermaid 图型集合；
- 抽查节点 ID、边和分组结构一致；
- 扫描目标目录，不存在第二张主图、未完成占位词或冲突标记；
- 确认 Git diff 不包含 query、sidebar、主题、依赖、构建输出或图片资产；
- 本地预览 `domain/lifecycle`、`command/api-client`、`command/internals/transport`、`event/index` 与扩充后的 `event/dispatch`；
- 对上述代表页检查桌面与窄屏下的主路径、标签换行和横向宽度。

## 实施分批

1. **领域模型**：新增 4 组图，重做快照图，验证与事件溯源图不重复；
2. **命令应用页**：新增概览、定义、发送、API Client、可靠性图；
3. **命令原理页**：新增等待运行时与传输图，保留并核对现有管线/完成图；
4. **事件与协作**：新增概览、Processor、Saga 图，扩充分发图，保留补偿状态机；
5. **全站验收**：双语结构、VitePress 构建、代表页桌面/窄屏视觉检查。

## 风险与控制

| 风险 | 控制 |
| --- | --- |
| 图过宽导致移动端难读 | 优先 `TB`、短标签和 `subgraph`；代表页必须做窄屏检查。 |
| 图与正文重复或冲突 | 每图只回答一个问题；正文继续拥有保证和例外。 |
| 中英文结构漂移 | 先完成中文事实图，再按相同节点 ID 和边结构编写英文。 |
| Mermaid 特殊字符导致构建失败 | 标签加引号，泛型和尖括号使用 HTML 转义，运行严格 VitePress 构建。 |
| 图无意中扩大运行时承诺 | 对高风险阶段、失败、重试和传输边界重新核对当前源码/测试。 |
| 与查询图表工作冲突 | 本任务完全排除 query、projection、data-access 与 sidebar 文件。 |

## 明确不做

- 不为了“视觉统一”给页面加入第二张装饰图；
- 不把代码示例截图化；
- 不用图取代错误说明、状态表、阶段表或能力限制；
- 不创建共享 Mermaid include、宏、组件或生成器；
- 不修改 VitePress 的 Mermaid 主题和全局颜色；
- 不把查询图表合并进本任务；
- 不在补图过程中顺带重写正文或修改运行时实现。
