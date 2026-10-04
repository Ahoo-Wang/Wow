---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 核对目标源码并保持只读，不把候选版本当成已发布制品
2. 使用 QueryPolicy.evaluate 产生附加 FilterExpression 或错误，不绑定 ABAC 标签，不恢复 around chain 或新建策略执行层
3. 说明 QueryFilter.prepare 可替换请求而 Policy 在全部 prepare 后以 AND 合并；两模型 Registrar 都装配策略
4. 具体策略根据 QueryContext 判断模型适用性，不适用返回 MatchAllFilter，空 Publisher 是错误；ABAC 标签仅用于 Snapshot
5. Schema 保存事实，Gateway 编排及调用公共校验，Backend 编译执行；HTTP scope 和限额留在 Handler
6. 要求真实 Policy Bean→HTTP Handler→Backend 最终谓词和拒绝零调用证据，区分 JVM 单测、进程内 WebTestClient、完整启动、实际存储与性能证明
