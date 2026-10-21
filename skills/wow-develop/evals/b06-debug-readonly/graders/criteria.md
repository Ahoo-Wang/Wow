---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Locates the first failing stage at compile time: without `@Saga` the compiler generates no `orderSagaHandlers` and writes no `OrderSaga.*` subscription into the Manifest fragment, so the node never subscribes it
2. Explains why the direct test passes: it calls the class's method itself, bypassing the generated handlers, while a `sagaSpec(orderSagaHandlers(...))` would fail to compile or find no handler
3. Names the evidence to read: the module's `META-INF/wow/manifest/<module>.json` subscriptions and the generated `*Subscriptions.kt` in `build/generated/ksp`, against a saga of the same module that works
4. Changes no file, and states what would falsify the hypothesis
