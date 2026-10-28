---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Sends the aggregation to the dataset's route, `POST /wow/dataset/analytics/order_wide/query` with `"kind": "aggregate"`, not to a snapshot route
2. Names the fields as the descriptor lists them, at the record root (`customer_level`, `amount`), with no `state.` prefix
3. Groups by `customer_level` with `terms` and measures `count` and the `sum` of `amount`
