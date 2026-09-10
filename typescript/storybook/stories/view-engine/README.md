# View Engine 场景维护

侧栏层级：

```text
View Engine
├─ 开始体验
│  └─ 全链路体验
├─ 订单业务流程
│  ├─ 接单与审核
│  ├─ 收款与放行
│  ├─ 备货与交付
│  ├─ 对账与结算
│  └─ 售后与关闭
├─ 开发接入
│  ├─ 最小接入
│  ├─ 我的工作视图
│  └─ 业务扩展
└─ 专项场景
   ├─ 数据展示
   ├─ 查询与筛选
   ├─ 视图与运行时
   └─ 组件与主题
```

开发验证承载持久化、HTTP 与独立包验收。CSF 的 `title` 决定层级，显式 `id` 保持书签与脚本地址稳定；回归必须有独立 id，不能直接继承展示故事的 id。

| 要修改的内容               | 唯一维护入口                                                                             |
| -------------------------- | ---------------------------------------------------------------------------------------- |
| 订单事实、金额和状态投影   | `packages/view-engine/examples/react/sales-order/model.ts`                               |
| 岗位权限、业务命令和下一步 | 同目录 `service.ts`                                                                      |
| 18 笔初始订单              | 同目录 `fixtures.ts`                                                                     |
| 字段、阶段视图和协议视图   | 同目录 `views.ts` 的工厂函数                                                             |
| 查询及视图保存             | 同目录 `querySource.ts`、`host.ts`                                                       |
| 页面组合、表单、详情和扩展 | 同目录 `OrderWorkbench.tsx`、`OrderForms.tsx`、`OrderDetails.tsx`、`OrderExtensions.tsx` |
| 最小可复制组件             | `stories/docs/RecordViewExample.tsx`，由 QuickStart 和中英文 wiki 共用                   |
| 单项契约的简化快照         | `record-view/fixtures.ts`，只用于专项，不扩展成第二套销售流程                            |

- 展示故事保持初始状态；交互断言放在同章节的 `.test.stories.tsx`，继承展示故事的 meta 和参数，使用 `!dev`、`!autodocs` 隐藏回归。
- 全链路只维护一份 `orders/lifecycle.play.ts`；窄屏回归归在 Lifecycle，最小接入只验证查询、排序和分页。
- 源码面板导入实际实现的 `?raw` 文件，不增加转发组件。业务示例只通过公开包入口使用引擎。
- 新增或移动章节时同步 `.storybook/preview.tsx`、章节链接及中英文文档。构建后的 `scripts/verify-storybook.mjs` 检查链接和回归隔离。
- 改业务规则运行 `salesOrder*.test.ts*`；改展示运行对应 Storybook 回归；移除文件或改变接入路径还需 `verify-package.mjs` 验证复制到独立应用后的类型与构建。

全量检查从仓库根目录运行：`pnpm test:unit`、`pnpm build`、`pnpm lint:view-engine`、`pnpm test:storybook`、`pnpm verify:view-engine`。文档按 `wiki/AGENTS.md` 生成并校验。
