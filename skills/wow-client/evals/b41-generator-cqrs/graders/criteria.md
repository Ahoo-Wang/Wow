---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Installs @ahoo-wang/wow-client with its peers (@ahoo-wang/fetcher, @ahoo-wang/fetcher-eventstream) and @ahoo-wang/wow-generator as a dev dependency, and runs wow-generator generate with --input pointing at the committed Manifest (not an OpenAPI document) and --output src/generated
2. Names what it writes: per context types.ts, contract.ts (the context's contract with its error codes, wait hops) and client.ts (the context's client), per aggregate aggregate.ts, commandClient.ts with CartCommandClient (addCartItem, removeCartItem and Stream twins) and queryClient.ts with CartSnapshotQueryClient, CartEventQueryClient and CartStateClient
3. Sends the command with new CartCommandClient({ fetcher }) and a wait plan (wait.snapshot()), and pages with CartSnapshotQueryClient.paged and the DSL from @ahoo-wang/wow-client/dsl on state.* fields
4. Commits the output with .wow-generator.json and never hand-edits it
