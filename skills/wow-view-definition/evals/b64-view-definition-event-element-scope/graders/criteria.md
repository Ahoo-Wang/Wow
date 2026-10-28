---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Declares events as an element field titled by name, with the event names (sales.order.OrderPaid and the others) labelled in business words and body.paidAmount among its element fields
2. Writes the condition as ELEMENT_MATCH on events whose predicate holds both events.name IN the OrderPaid name and events.body.paidAmount GT 1000
3. Never writes a root-level condition on events.body.paidAmount or a separate root events.name condition, and uses the same element predicate in the metric's filter
4. Keys the rows by a field unique per commit (commandId or requestId) with paged paging, or by aggregateId only with the cursor, since the descriptor's `cursor-unique-sort` constraint appends aggregateId
