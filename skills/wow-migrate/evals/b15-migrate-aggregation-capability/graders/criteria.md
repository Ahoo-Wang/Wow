---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 对照源版本与目标版本确认聚合路由、Gateway 和 Backend 契约
2. 不把源码兼容、启动或普通查询成功视为聚合运行时兼容
3. 检查 routed SnapshotQueryBackendFactory 实际选择和自定义 Backend 的 aggregate 实现
4. 给出最小适配与真实生成端点验证门禁，不新增控制器或兼容基础设施
5. 区分源码适配、运行时验证与发布状态，并说明该场景没有数据转换证据或需求
