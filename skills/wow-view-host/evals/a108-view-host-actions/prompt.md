---
name: a108-view-host-actions
tags: [activation, trigger, actions]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

给我们 React 后台订单工作台接上发货、取消两个 Wow 命令：已付款才能发货，取消要先确认，批量也能做。用的是 @ahoo-wang/wow-view-engine。
