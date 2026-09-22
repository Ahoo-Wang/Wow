# Storybook 维护约定

Storybook 是可运行的接入文档，也承载浏览器交互回归。导航按能力组织，代码按模块就近维护。

## 示例与回归

- `*.stories.tsx`：展示组件、初始参数、说明和可手动操作的场景。允许初始化读取和无副作用的渲染断言。
- `*.test.stories.tsx`：导入展示故事，复用参数和演示实现，安装复杂 `play`。使用 `['!dev', '!autodocs', 'test']`，保留测试执行并隐藏默认导航与文档入口。
- `*.play.ts`：较长交互的具名实现，就近维护。不要求为简单断言单独建文件。
- `shared/`：只有真实复用的场景外壳和 Ant Design Provider。模块显式声明装饰器，不通过故事标题选择 Provider。

普通展示不能依赖自动测试来创建初始数据或完成异步请求。打开页面后，筛选、保存、创建和删除均由使用者触发。

## 文档与状态

`.storybook/DocsPage.tsx` 使用原生文档块展示一个主示例、参数和独立场景链接，避免将所有场景同时挂载。复杂包装器的代码面板引用真实接入源码。

修改全局 fetch 或 Viewer 默认注册器的示例使用独立 iframe，并通过 `beforeEach` 返回清理函数。共享夹具的数据可以复用，可变状态不能跨场景共享。未知来源的请求交给原始 fetch；受控失败只作用于示例 API。

View Engine 的故事在 `view-engine/`，按界面分为数据视图、分析视图与仪表盘视图，每个故事只呈现一种状态：有数据、空结果、加载中、查询失败、待修复、面板不可用。状态由 `fixtures.ts` 里的假数据源决定，引擎与存储每次挂载都新建，因此保存、改名与删除是真写入，也不会跨场景残留。

假数据源按引擎实际发出的查询作答：`rowSource.ts` 把 Wow 查询翻译成 MongoDB 查询，交给 `mingo` 做筛选、排序、分页与聚合，所以表格、汇总行和图表就是这些条件选出的结果。翻译不了的算子直接报错，表现为查询失败，而不是给出一个看似合理的错误答案。每个界面的 `*.test.stories.tsx` 断言这些结果。

## 真实后端

`view-engine/DataConsole.stories.tsx` 是**补偿控制台**，一个数据控制台：一个工作台直连 Wow 补偿服务，用真实数据和真实数据量检验体验。记录视图与分析视图在同一个视图列表里切换；每行带操作列（重试、强制重试、标记可恢复性，按这条执行的状态开放），选中多行时同样的命令成批执行，一条结果条说明做成了几条。字段定义按服务的查询 Schema（`GET /execution_failed/snapshot/schema`）人工对齐，见 `compensation.ts` 里 `executionFailedDefinition` 的说明。

- 服务地址是故事的 `host` 参数，可在 Controls 面板随时切换；初始值取 `STORYBOOK_WOW_COMPENSATION_HOST`，未设置时为 `http://localhost:8080`。
- 写操作会真实写回服务，只连接测试环境。
- 真实数据每次都不同，这些故事标记为 `!test`，不进入回归测试；文档页用 `docs.autoMount: false` 只列出场景链接，不挂载示例，因此打开目录不会调用服务。
- 变的只是数据；定义、系统视图和读取快照的方式是确定的，View Engine 的规则一变就可能让它们失效。`DataConsole.test.stories.tsx` 把同一个控制台指向 `compensationService.ts` 里的录制服务（它也按服务的规则应答三条补偿命令），让这类失效在 CI 里失败，而不是等有人打开目录才发现。

## 检查命令

```bash
pnpm test:storybook
pnpm build-storybook
pnpm lint:stories
pnpm typecheck:stories
```

`build-storybook` 包含静态索引检查：验证首页地址与回归标签。`typecheck:stories` 按应用看到的方式检查故事：对照各包构建出的类型声明，因此先运行 `pnpm -r --filter './packages/*' build`。

先运行 `pnpm storybook`，再运行以下真实浏览器检查；使用独立的无头浏览器：

```bash
node scripts/verify-storybook-browser.mjs
```

文档浏览器检查也可接收服务地址：`node scripts/verify-storybook-browser.mjs http://127.0.0.1:6006`。

移动故事时同步检查首页、验证脚本和测试中的地址。历史迁移记录见 `docs/superpowers/plans/2026-09-08-storybook-migration.md`。
