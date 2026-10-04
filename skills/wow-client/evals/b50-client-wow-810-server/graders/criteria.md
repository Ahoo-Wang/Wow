---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Imports and, eq, ownerId and listQuery from @ahoo-wang/wow-client/legacy because Wow 8.10 understands only the Condition model
2. Keeps the query clients and everything else imported from the root entry
3. Avoids getById/getStateById, which send a FilterExpression, and uses single/singleState with a /legacy aggregateId query for one aggregate
4. Notes that /legacy is removed in v10 and that raw() is refused by servers from 8.11 on
