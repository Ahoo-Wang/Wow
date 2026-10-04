---
name: b71-view-host-from-commands
tags: [behavior, positive, actions]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

订单聚合有 ship_order（仅已付款）、cancel_order（发货前，需填原因）两个命令。给订单工作台声明这两个操作，并写测试。
