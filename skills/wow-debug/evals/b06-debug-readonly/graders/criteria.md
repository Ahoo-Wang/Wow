---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 从症状沿 handler 发现与注册路径定位第一个失败阶段
2. 读取下游 OrderSaga，并从目标 Wow 版本核对 AutoRegistrar 与 ProcessorMetadataParser 契约
3. 用证据说明缺少 StatelessSaga 标注为何不影响直接调用却阻止运行时发现
4. 不修改文件，并说明可证伪条件与影响边界
