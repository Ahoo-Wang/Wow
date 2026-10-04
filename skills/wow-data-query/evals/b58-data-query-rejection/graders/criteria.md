---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Applies the ARRAY_EQUALITY constraint from the descriptor before sending and does not send EQ with an array operand
2. Offers CONTAINS_ALL or an element condition as the granted alternative and states the semantic difference
3. If a query is rejected, reads bindingErrors code and name and retries at most once before handing a diagnosis to wow-develop
