---
title: 'wow-react 参考'
description: '@ahoo-wang/wow-react 包：它的 hooks、hooks 的选项和结果，以及生成的符号索引和 API 报告在哪里。'
---

# wow-react 参考

`@ahoo-wang/wow-react` 在 React 组件中保存一个 Wow 查询：查询本身、它的结果、加载状态和错误，作为 React 状态，基于 `@ahoo-wang/wow-client` 的查询类型。怎样使用它，见 [React Hooks](../../../typescript/react)；本参考列出它导出什么。

## 入口 {#entry}

只有一个入口 `@ahoo-wang/wow-react`。它的[符号索引](/reference/typescript/wow-react/symbols)（英文）列出它导出的每个名字。

| Hook | 运行 | 客户端方法 |
| --- | --- | --- |
| `useSingleQuery` | 单条查询 | `single` |
| `useListQuery` | 列表查询 | `list` |
| `usePagedQuery` | 分页查询 | `paged` |
| `useCountQuery` | 计数 | `count` |
| `useAggregateQuery` | 聚合 | `aggregate` |
| `useCursorQuery` | 游标查询，逐页读取 | `cursor` |
| `useStreamQuery` | 以服务端推送事件返回的列表 | `streamRows` |

每个 hook 接收一个 `Use…Options`，返回一个 `Use…Return`，两者都有导出。每个 hook 都运行你的 `execute`，即生成客户端的某个方法；每个 hook 都有第二个重载，以你自己的查询类型（它的查询类型的子类型）提供类型。

共享的类型也有导出：`QueryStatus`（`'idle' | 'loading' | 'success' | 'error'`）、`QueryExecutor`、`StreamExecutor`，以及每个 hook 的选项和结果所扩展的 `QueryHookOptions` 和 `QueryHookReturn`。

## 生成的部分 {#the-generated-half}

符号索引页由包的 `scripts/api-report.mjs` 从构建出的声明生成：每个导出的名字、它的种类以及它 TSDoc 的第一句话。同一次运行把入口的完整签名保存在它的 API 报告 `test/api/root.api.md` 中，构建结果与提交的报告或页面不同时就会失败。

## 另见 {#see-also}

- [React Hooks](../../../typescript/react)：hooks 的实际用法。
- [wow-client 参考](../wow-client/)：hooks 所接收的查询类型。
- [兼容性与版本](../../../typescript/compatibility)：React 19 以及其他 peer 依赖。
