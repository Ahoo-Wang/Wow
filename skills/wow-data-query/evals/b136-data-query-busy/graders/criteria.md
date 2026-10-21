---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Treats 429 wow.query.busy as the store's query bulkhead being full: waits Retry-After seconds and retries the same query once, without changing it
2. Builds the count with "kind": "count", a filter of EQ state.status PAID and YESTERDAY on the date field with zoneId Asia/Shanghai
3. Reports the count with exact true or false (a lower bound when false) and the time zone, and hands a repeated 429 or a 5xx to wow-develop instead of looping
