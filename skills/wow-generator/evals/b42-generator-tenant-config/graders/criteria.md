---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Users aggregate command and query clients are generated under src/generated/user-service/user_service/users/ (output directory, then the context alias, then the aggregate)
2. Tenant-based resource attribution is configured (ResourceAttributionPathSpec.TENANT inferred from /tenant/{tenantId} paths)
3. The default_delete_aggregate operation generates a defaultDeleteAggregate method
4. wow-generator.config.json is created with apiClients.users.ignorePathParameters and passed with -c when it is not in the working directory (there is no .fetcherrc.json; this is the only config file the CLI reads)
