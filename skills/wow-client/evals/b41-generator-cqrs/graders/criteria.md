---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Runs wow-generator generate and installs @ahoo-wang/wow-client with its fetcher peer dependencies for the generated runtime imports
2. CartCommandClient is generated with an addCartItem method (commands need an inline requestBody and responses['200'] referencing #/components/responses/wow.CommandOk)
3. cartQueryClientFactory (QueryClientFactory imported from @ahoo-wang/wow-client) is generated for snapshot queries, and CartDomainEventType comes from the event.list_query operation
4. Generated code uses Wow CQRS patterns correctly (root-level `ecommerce.cart` tag plus snapshot_state.single and snapshot.count operations); the aggregate lands in src/generated/ecommerce/ecommerce/cart/ (output directory, then the context alias, then the aggregate)
