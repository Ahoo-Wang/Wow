---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 先确认下游实际版本、精确源码及 CursorQuery/CursorPage、Gateway 和生成 HTTP 合同，缺失证据明确标记未验证
2. 使用聚合绑定 SnapshotQueryGateway 的受管 typed/dynamic cursor，不把原始 BackendFactory 当应用入口
3. 首次 cursor 为 null，后续 filter/sort 不变且原样回传 nextCursor，nextCursor 为 null 时结束；token 不透明且不保存授权状态
4. 有效排序要求独立 CURSOR_SORT 能力、单值且无 Mask 或受保护别名、稳定唯一排序；不能仅凭 SORT 能力准入，由框架补唯一 tie-breaker
5. isNotEmptyString 区分空白、null、缺失与空集合；HTTP expensive-operator guard 可在传输边界拒绝，不能改变进程内原生能力
6. 每页重新执行 scope、QueryPolicy、准备和 Mask；区分 Schema 事实与 Gateway 准入，分别验证后端、路由、空页和非法 token，不发明新执行层或 token codec
