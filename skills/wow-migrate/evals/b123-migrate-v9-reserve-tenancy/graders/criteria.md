---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 指出 V10 的 `reserve(namespace, key)` 默认按租户唯一（`ReservationTenancy.TENANT`），而 9.x `PrepareKey` 是全局的；照抄会把全局唯一的登录名悄悄变成租户内唯一
2. 逐个键决定范围：登录名用 `ReservationTenancy.SYSTEM`（跨租户唯一），员工工号用 `TENANT`；对拿不准的键要求向用户确认并记录，而不是默认选择
3. 改写为在 decide 中调用 `scope.reserve(namespace, key, tenancy)`，返回 false 时 `Decision.reject` 一个 `@ErrorCode` 码；不再需要 `usingPrepare` 的回滚（内核在追加失败时放弃预留、追加后确认）；改名用同一次决定里的 `reserve` 新键加 `release` 旧键
4. 否定「预留数据不用迁移」：不导入则切换后 9.x 已占用的键在 V10 空闲，唯一性失效；在 `migration-map.json` 的用户聚合下写 `reservations`：登录名 `{"from": "login_name", "namespace": ..., "tenancy": "SYSTEM", "ownerId": "userId"}`，工号 `{"from": "employee_no", "namespace": ..., "tenancy": "TENANT"}`（值本身是 ID，不需要 `ownerId`）；说明工具只导入永久（已确认）的键、跳过待确认与过期的键、租户取所属聚合 9.x 流记录的租户，`compare` 核对每个已确认键在 V10 有同 owner 的预留
5. 把数据导入、`compare` 结果与切换列为 MISSING EVIDENCE；不添加 9.x `PrepareKey` 兼容层
