---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. It had no shell and no write tool: code shown in the answer satisfies a point that asks for a rewrite. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 指出照搬不对：9.x 同一 `givenEvent { … }` 里并列的 `whenCommand` 各自从该 given 开始，是互相独立的场景；V10 测试 DSL 中连续的 `whenCommand` 依次链接，每条命令作用于上一条留下的聚合状态，照搬会让后两个场景在错误的状态上运行（失败或错误地通过）
2. 改写为 `aggregateSpec(cartModel(...)) { given(CartItemAdded(...)) … }`，三个并列命令各放进一个 `fork(…) { whenCommand(…) { … } }` 分支，而不是顺序调用
3. `expectEventType(X::class)` 改为 `expectEventTypes(X::class)`（或 `expectEvent<X>()`），`givenOwnerId("customer-1")` 改为作用域（如 `given(…, scope = Scope("customer-1"))`），断言保持原意
4. 不为 9.x 的并列语义添加兼容层、自定义包装或 `TODO(wow-migrate)` 来"恢复"旧行为
