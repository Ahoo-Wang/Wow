---
name: a120-data-query-dataset
tags: [activation, trigger, dataset]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

我们的 Wow 分析服务在 staging 上提供了 ClickHouse 宽表数据集 analytics.order_wide，帮我查上个月各城市的 GMV 前十，直接给数字。
