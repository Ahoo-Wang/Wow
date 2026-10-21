---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Uses the generated CartCommandClient.addCartItem(body, { id: cartId, wait: { plan: [wait.snapshot()] } }) (or CommandGateway.send on /{context}/cart/{id}/add-cart-item), with no ownerId in the path: the service takes the owner from the caller
2. Handles a refusal as a rejected promise: catch WowProblemError and show its detail or map its code, since a failed command no longer resolves with errorCode
3. Notes that if price is a BigDecimal in the service, the V10 type is a string ('19.90'), sent exactly through the generated client
4. Notes the error code strings changed (wow.* and <context>.cart.<code>) and that the type check, lint and a call against the V10 service are the gates
