---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Writes a `@Saga` with typed handlers (`StateTypedEvent<OrderState, OrderSubmitted>` or the event itself) deriving `ReserveStock` per line as returned `CommandRequest`s, ids deterministic
2. Handles the refusal with `@OnRejected` taking `CommandReceipt.Rejected<ReserveStock>` and returning a `CancelOrder` for the order the reservation was for, and says that without it the rejection would park the order for the saga
3. Says the cross-context commands travel on Kafka (`wow-kafka`, the inventory's `command-intake`, its command topic) and that a node without a command transport does not start, with `wow.transport` or an `InMemoryTopics` bean for local runs and tests
4. Tests with `sagaSpec(<processor>Handlers(...))` (the generated handlers): `whenEvent` expecting the commands, `expectDeterministic()`, and `whenRejected(command, code, message)` expecting the cancellation
