---
name: b126-migrate-v10-standby-cutover
tags: [behavior, migration, data-cutover, planning, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

我们的 9.x 订单服务要切到 V10。wow-migrate-data 的 import 已经跑完，tail 从那以后一直在跑。我们想在切换前就把 V10 节点部署起来、先对生产数据核对读取，切换时停写尽量短。给出切换窗口的步骤。本对话没有任何生产授权。
