---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Builds the filter with filter.and, filter.eq, and filter.yesterday using zoneId 'Asia/Shanghai' rather than hand-written JSON or deprecated Condition helpers
2. Sorts with desc('state.createdAt') and paginates with pagination { index: 1, size: 20 }
3. Calls pagedState on a SnapshotQueryClient (or one created from QueryClientFactory)
4. Imports every builder from @ahoo-wang/wow-client
