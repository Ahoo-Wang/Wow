---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 核对下游设计与精确目标源码，不把框架已有测试当成该应用自定义装配的证明
2. 识别普通 prepare 无法守护强制 owner 条件，建议由 QueryPolicy 在所有 prepare 后追加 AND，不把业务策略移入 Schema 或 Backend
3. 指出 Gateway mock 单测不能证明容器注册、实际 Handler 选择、最终 Backend 谓词或拒绝零调用；也不把手工路由 WebTestClient 扩大为完整启动与路由发现
4. 按已授权兼容范围评估构造器变化，承认实现二进制签名变化但不强加兼容桥；保留公共接口及旧 Condition 约定
5. 区分 Schema 事实、Gateway 公共准入与策略编排、Backend 原生执行；报告可操作 findings 并保持只读
