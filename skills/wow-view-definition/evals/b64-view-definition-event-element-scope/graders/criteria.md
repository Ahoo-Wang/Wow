---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Declares body as an elementMatch field titled by bodyType, with event-type options labelled in business words and body.paidAmount taken from the OrderPaid variant
2. Writes the condition as ELEMENT_MATCH on body whose predicate holds both body.bodyType IN the OrderPaid type and body.body.paidAmount GT 1000
3. Never writes a root-level condition on body.body.paidAmount or a separate root bodyType condition, and uses the same element predicate in the metric's filter
4. Uses a cursor rowKey equal to the CURSOR_UNIQUE_SORT appended identity (id)
