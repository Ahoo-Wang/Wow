---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Configures one Fetcher for the service (registered as the default or passed as fetcher) instead of per-call headers
2. Applies @ahoo-wang/fetcher-cosec's CoSecConfigurer with a CoSecTokenRefresher on a separate Fetcher, and stores the token with tokenStorage.signIn after login
3. Relies on CoSec's resource attribution to fill {tenantId}/{ownerId} rather than hard-coding them into basePath
4. Keeps the generated clients' bounded-context prefix unless the app calls the service directly without a gateway
