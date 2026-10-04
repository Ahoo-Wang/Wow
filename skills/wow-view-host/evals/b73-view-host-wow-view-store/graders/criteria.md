---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Pushes back on passing tenant or user ids: WowViewStore takes only a fetcher and permissions, and fetcher-cosec's interceptors fill {tenantId} and {ownerId} from the token
2. Applies CoSec to the store's fetcher and gives createShared and changeAudience only to the ops-lead role in permissions
3. States the CoSec gateway rules for the view-store paths, including that claim and share need both sub == {ownerId} and the shared-write role and are excluded from the personal rule
