---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 删除 9.x 升级器，在聚合上声明 `@AggregateRoot(retiredEvents = ["OrderArchived"])`（或日志中存储的完整名），不保留事件类与溯源函数；说明退役事件的记录在加载时被跳过、流的版本照常计数，未声明的未知事件名仍会加载失败
2. 指出只含 `order_archived` 的流若用 `drop` 会变成空版本，数据工具的映射核对会在导入前报告并拒绝，因此映射写成 `{"from": "order_archived", "to": "OrderArchived", "retire": true}`，且该名字必须在目标聚合的退役列表（Manifest 的 `retired`）里，否则核对失败
3. 处理 `archive_order` 命令：这些流仍记录该命令名，映射表需要为它给出 V10 命令映射（或说明须向用户确认如何处理），不靠猜测
4. 退役名不得与现有事件、命令名或 `@Alias` 旧名相同；兼容门把从 `events` 移到 `retired` 视为退役而非破坏性删除
5. 以映射核对（`check`）通过、导入后 V10 重建状态与 9.x 快照一致为数据验收，并把生产数据、导入与切换列为 MISSING EVIDENCE；不添加 9.x 兼容层，不手改存储的事件
