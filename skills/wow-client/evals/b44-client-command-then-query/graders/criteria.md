---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Imports CommandClient, SnapshotQueryClient, CommandStage, and waitStrategy (or CommandHeaders) from @ahoo-wang/wow-client and Fetcher from @ahoo-wang/fetcher
2. Binds ownerId through urlParams.path (or a resource-attribution interceptor) instead of hard-coding it into basePath
3. Waits for CommandStage.SNAPSHOT with waitStrategy({ stage: CommandStage.SNAPSHOT }) (or the CommandHeaders.WAIT_STAGE header) before reading state
4. Treats a result whose errorCode is not ErrorCodes.SUCCEEDED as a failed command, and reads a refused request with toWowError
5. Reads state with getStateById (or singleState with filter.aggregateId) using the aggregateId from the CommandResult
