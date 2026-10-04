---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 报告业务请求转到原始 Backend 绕过 Gateway 的 prepare、受信 scope、QueryPolicy、默认条件、公共准入、Mask 和终止观察；不能仅凭 Backend 相同就判定治理等价
2. 以回到受管聚合级 Gateway 为最小修复方向，不把业务策略搬到 Schema 或复制进 Backend
3. 区分 WebFlux Handler 提取 scope 与 Gateway 在全部 prepare 后追加 scope/Policy，进程内调用不自动继承 HTTP scope
4. 给出实际源码和触发场景证据，保留只读，不新增包装器、兼容层或 Factory 抽象
