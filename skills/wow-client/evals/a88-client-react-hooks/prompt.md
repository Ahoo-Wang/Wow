---
name: a88-client-react-hooks
tags: [activation, trigger, react, package-rename]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

React 页面要用 Wow 的分页查询 hook 展示订单快照，之前从 @ahoo-wang/fetcher-react 根入口导入 usePagedQuery，现在要换到新包。
