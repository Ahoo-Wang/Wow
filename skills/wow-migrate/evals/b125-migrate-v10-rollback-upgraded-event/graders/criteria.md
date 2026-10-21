---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Says redeploying 9.x is not a rollback once V10 has written: 9.x lacks the streams V10 appended, which would be lost
2. Gives the path: stop V10's writes and keep 9.x stopped, run migrate-data export --dry-run, then export, check its per-aggregate stream counts (EQUAL, or DIFFERS with exit 3), then start 9.x
3. Explains that every stream V10 wrote with OrderCreated at revision 2, past the map's toRevision 1, has no 9.x form, so the export refuses (REFUSED) before writing anything, and that new orders created in V10 therefore block this rollback; leaves the way forward to the user's decision instead of inventing a down-converter
4. Notes what export never carries back (reservations, parked entries, projections' views) and that 9.x's snapshots stay stale until rewritten
5. Confirms the pinned wow-migrate-data provides export before relying on it, and asks for production authorization for each step rather than claiming to have run anything
