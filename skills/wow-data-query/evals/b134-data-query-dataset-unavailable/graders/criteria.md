---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Explains that 409 wow.query.dataset-unavailable means the dataset is being revised (its sources changed and it needs a new revision), not a malformed query, so the query is not changed to work around it
2. Re-reads GET /wow/dataset and the dataset's descriptor (GET on /wow/dataset/analytics/order_wide/query) before trying again later
3. Offers an alternative only with its caveat: the per-city answer from another dataset or an aggregate's snapshots, saying what differs, or tells the user to ask the data owner to revise it
4. Never fabricates the numbers and uses credentials from the environment
