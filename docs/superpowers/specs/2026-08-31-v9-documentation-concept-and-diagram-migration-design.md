# V9 查询术语与图表源统一设计

## 背景

V9 已把原 `QueryService` 职责拆分为受管入口 `QueryGateway` 与存储执行 `QueryBackend`，但部分中英文文档仍沿用“查询服务”。仓库同时保留了 Mermaid 能表达的 PlantUML 图源及其生成 SVG，与“Mermaid 优先、不要并存生成 SVG”的文档规则冲突。

## 目标

1. 用户可见文档使用 `QueryGateway`、`QueryBackend` 和“查询能力”表达真实职责，不再把 V9 API 统称为 `QueryService`。
2. Mermaid 支持的图表以 Mermaid 作为唯一可维护源，不提交对应的生成 SVG。
3. Mermaid 不支持的用例图继续使用 PlantUML；logo、徽章、截图等非图表 SVG/图片不受影响。
4. 中英文文档保持结构与概念对称。

## 非目标

- 不修改任何运行时代码、公共 API、序列化或 REST 合同。
- 不修改 V9.0.x 的 `Condition` 兼容窗口。
- 不处理仅作为前端别名的 `DynamicDocument`。
- 不增加 Mermaid、PlantUML 或 SVG 生成依赖及构建任务。

## 术语规则

| 场景 | 规范术语 |
| --- | --- |
| Spring 注册、WebFlux 受管调用、typed 物化与过滤链 | `QueryGateway` / 查询网关 |
| MongoDB、Elasticsearch、NoOp 与路由执行层 | `QueryBackend` / 查询后端 |
| TCK | `SnapshotQueryBackendSpec`、`EventStreamQueryBackendSpec` / 查询后端规格 |
| 不指向具体框架类型的产品能力 | 查询能力 |
| V8 到 V9 迁移表 | 允许出现旧 `QueryService` 名称，但必须明确它是已删除的 V8 类型 |

`documentation/docs/{en,zh}/guide/query.md` 中旧 `query-service`/“查询服务”锚点同步改为 Gateway 命名；不保留隐藏的 V8 文档锚点。投影概览图补全 `WebFlux/API Client -> QueryGateway -> QueryBackend -> Read Model`，使入口策略与存储执行边界可见。

## 图表迁移规则

### 保留

- `document/design/uml/Event-Compensation-UserCase.puml`：用例图，Mermaid 不支持。
- `document/design/uml/layout.puml`：仅作为上述 PlantUML 用例图的样式依赖。
- 用例图在文档展示所需的 SVG。
- `logo.svg`、`kaicode-2026-wow.svg`、仪表盘截图等非图表视觉资产。

### 转换

- 状态图转换为 Mermaid `stateDiagram-v2`。
- 时序图转换为 Mermaid `sequenceDiagram`。
- 类图转换为 Mermaid `classDiagram`。
- 活动图、组件关系、上下文映射与消息流转换为 Mermaid `flowchart`。
- 命名树转换为 Mermaid `mindmap`。
- 一个 PlantUML 文件包含多个独立图时拆成职责单一的 `.mmd` 文件，例如聚合建模三种模式、消息类图与消息流、订阅者类图与时序图。

用户文档直接使用 fenced `mermaid`，设计目录中的独立图源使用 `.mmd`。迁移后删除已无引用的对应 `.puml` 与生成 SVG；不生成 Mermaid SVG 副本。

## 分批交付

### 批次 1：V9 查询术语

- 更新中英文模块依赖、扩展、TCK、WebFlux、投影、配置参考与查询入口文档。
- 迁移旧文档锚点和所有仓库内引用。
- 不改图表源格式，只修正投影 Mermaid 中的职责链。

验证：扫描迁移文档之外的 `QueryService`/“查询服务”残留，运行 `pnpm docs:build`。

### 批次 2：高流量文档 SVG

- 把 README、介绍、BI、事件溯源与架构页引用的架构/数据流 SVG 改成 fenced Mermaid。
- 删除失去引用且属于生成图表的 public/design SVG；保留非图表资产。

验证：扫描被删除 SVG 的引用，运行 `pnpm docs:build`，并确认 README 的 Mermaid 语法可被 GitHub 渲染。

### 批次 3：设计图源清理

- 转换剩余 Mermaid 支持的 PlantUML 文件为 `.mmd`。
- 拆分包含多张图的源文件，删除对应生成 SVG。
- 仅保留用例图 PlantUML、`layout.puml` 与必要用例图 SVG。

验证：确认剩余 `.puml` 仅为用例图及其样式依赖；执行 Mermaid 语法校验和文档构建。

## 验收标准

- 迁移文档之外不存在把 V9 Gateway/Backend 称为 `QueryService` 的用户可见文本。
- Mermaid 支持的图不再保留 PlantUML 或生成 SVG 双份来源。
- 仓库中保留的 PlantUML 仅用于 Mermaid 不支持的图形。
- 中英文文档构建成功，所有本地图片链接和锚点引用有效。
- `git diff --check` 通过，不包含生成的 VitePress `dist`。
