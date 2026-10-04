---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Writes the definition with defineView over the committed descriptor and records its version (sha256:order-snapshot-fixture-v1) beside it
2. Lists only the fields the customer-service audience reads, filters or groups by, including the record's identity or the rowKey it chooses, and invents no path
3. Labels every field, status value, system view and metric with text(key) and gives the words in each language the host serves, in business words
4. Leaves capabilities open rather than restating the descriptor, narrowing only for the audience (for example analysis: false on the order number)
5. Writes the 待发货 system view with status IN PAID sorted by paidAt ascending, and the analysis view by channel and day with display names such as 订单数 and 实付
6. Self-checks with an admit test over the committed descriptor in each language and reports [] or each finding kept on purpose with its reason, without re-verifying paths and capabilities by hand
