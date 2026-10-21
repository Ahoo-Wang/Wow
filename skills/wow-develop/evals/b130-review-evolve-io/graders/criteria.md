---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Flags as blocking that the evolve function calls a service: evolve must be pure (no services, no I/O), because it runs again on every load and replay, so a price change rewrites history and a slow or failing service breaks loading
2. Directs the price into the decision: the command function reads the price (a service of decide) and the event carries it (`OrderItemAdded(sku, quantity, unitPrice)`), evolve only adds
3. Notes the event's shape changes after release need `@Revision` and an `@Upgrade` (or a new event), checked by the Manifest compatibility check
4. Edits nothing and names the test it would add (an aggregate spec replaying the events with no service)
