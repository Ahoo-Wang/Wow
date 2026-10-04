---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Finds no points field (and no alias) in the descriptor
2. Does not invent or guess a field path such as state.buyer.points and adds no column or analysis field for it
3. Says the field is missing and where it would have to come from (a read-model field or model declaration), and offers what the descriptor does list, such as grouping by state.buyer.level
