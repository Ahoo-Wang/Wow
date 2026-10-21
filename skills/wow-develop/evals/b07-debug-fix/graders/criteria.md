---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Keeps failing evidence first: a saga spec through the generated handlers (`sagaSpec(orderSagaHandlers(...))`) or the Manifest's missing `OrderSaga.*` subscription, not the direct method test that passes
2. Restores only `@Saga` (and its import) on the downstream OrderSaga, without touching the Wow framework or the handlers' logic
3. Reruns the same spec and the module's check, which compiles the generated handlers and the Manifest
4. Reports the reproduction, the fix and the remaining risk (the direct test hid the gap: replace it with a sagaSpec)
