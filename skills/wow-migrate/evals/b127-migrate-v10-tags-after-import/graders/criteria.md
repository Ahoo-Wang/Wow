---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Says the import is not broken: V10's @Tags derives an aggregate's tags from its current state, the last value of each key, while 9.x kept the union of every value it derived, so orders whose province changed differ by design
2. Treats $tags on those orders as an explained difference to sign off, not as a reason to edit the data or the comparison
3. Points out the consequence for the ABAC rule: staff of an order's former province no longer see it in V10, and the user must decide whether that is intended before the cut-over
4. Keeps compare as the cut-over gate: the cut-over goes ahead only when every difference kind is explained and signed off
