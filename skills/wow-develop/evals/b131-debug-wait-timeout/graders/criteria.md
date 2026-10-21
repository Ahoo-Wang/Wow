---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Says the command may well have committed: a wait timeout is not a failed command, and the retry's refusal fits that; retrying must reuse the same `Wow-Request-Id`
2. Reads the last stage reached from the problem, then the order's state (`GET …/{id}/state`) to confirm the commit
3. Suspects the handled stage: the saga's subscription lagging or the order parked for it, checked through the parked entries (`/wow/ops/parked` or the console) and the health `wow` group's lag, before any timeout is raised
4. Changes nothing, states one falsifiable hypothesis and the check that tells it apart
