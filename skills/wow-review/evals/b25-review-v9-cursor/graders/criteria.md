---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 报告 raw Backend 续页绕过 scope、QueryPolicy、prepare、默认条件、公共校验、Mask 和观察，token 不保存 tenant 授权；每页均应使用受管 Gateway
2. 拒绝 masked 字段及受保护别名作为 cursor 排序，要求独立 CURSOR_SORT 能力、单值且稳定唯一的排序，不用已移除的 EXACT/mode 元数据推断能力
3. 报告解析和记录不透明 token 值破坏 Backend codec 边界并可能泄露数据，应原样回传且不跨 Backend 复用
4. 每个 finding 给出严重度、影响、触发条件、源码证据和最小修复方向；保持只读，不添加包装器、codec 或兼容代理
