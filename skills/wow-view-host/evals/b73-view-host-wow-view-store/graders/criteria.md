---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Pushes back on passing tenant or user ids: WowViewStore takes only a fetcher and permissions, no path names the tenant or owner, and the V10 view store service reads the caller (tenant, user, roles, CoSec-App-Id) from the principal the fetcher's CoSec interceptors send
2. Applies CoSec to the store's fetcher and answers createShared and changeAudience by the role the service checks, view-store.shared-writer, granted to the ops-lead users in the identity provider rather than checking an ops-lead role the service does not know
3. States that the service, not permissions, authorizes each write: shared writes need view-store.shared-writer, stored system views view-store.system-writer (editSystem only for it), and a user writes only its own views
