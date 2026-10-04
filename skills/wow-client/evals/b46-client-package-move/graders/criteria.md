---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Replaces @ahoo-wang/fetcher-wow with @ahoo-wang/wow-client in dependencies and every import specifier
2. Adds @ahoo-wang/wow-react and imports usePagedQuery from it instead of the @ahoo-wang/fetcher-react root
3. Upgrades @ahoo-wang/fetcher-react to 5.1.5 or later because wow-react imports only its core and fetcher subpaths
4. Keeps @ahoo-wang/fetcher, @ahoo-wang/fetcher-decorator, and @ahoo-wang/fetcher-eventstream under their existing names and runs the type check
5. Fixes the call sites the type check reports for APIs changed in the first wow-client release (for example ErrorCodes.isSucceeded, new CommandClient<C>, aggregation builder arguments) and imports any Condition builders from @ahoo-wang/wow-client/legacy
