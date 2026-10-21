---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Reads the events model's descriptor (GET on /{context}/cart/events/query) and checks that the body field quantity of the CartItemAdded event is filterable (only an event still at revision 1 is)
2. Puts the quantity condition inside one ELEMENT_MATCH on events together with the name condition (the event's full name, <context>.cart.CartItemAdded), so both apply to the same event
3. Sends "kind": "count" to the events query route and says it counts commits (event streams), not events, reporting the number with the exact body
