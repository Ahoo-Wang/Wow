---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Uses the V10 surface, not 9.x: an `-api` module on `wow-api` (the Gradle plugin `me.ahoo.wow.api`), a domain module on `wow-kernel` with the Gradle plugin `me.ahoo.wow` (or KSP with `wow-compiler` applied by hand), and a server on `wow-spring-boot` plus a backend module (the Gradle plugin `me.ahoo.wow.service`), or one module of `me.ahoo.wow.service` alone; it does not use `wow-spring-boot-starter`, `wow-core` or a 9.x `AggregateSpec<A, S>` subclass.
2. Names the context with `@BoundedContext` using lower case, digits and hyphens only (no dot), such as `catalog`, and says a dot is not allowed because it separates the parts of type names.
3. Returns the event type from a decide function and rejects with `reject(code, message)` (or returns `Decision` with `@OnCommand(produces = [...])` only where the type cannot name the events), with the code a `const val` annotated `@ErrorCode` in the domain module; the command body carries no aggregate id.
4. Tests with `aggregateSpec` from the V10 test DSL (`me.ahoo.wow.test`, `testImplementation("me.ahoo.wow:wow-test")`), building the model with the generated factory `productModel(…)` (its context defaults to the module's), with `given`, `whenCommand` and `expectRejected` or `expectEvents`.
5. Names what the compiler generates: the `productModel` factory (its context defaulting to the module's) and the `ProductModel.TYPE` object beside it, a `ModelProvider` binding index named after the module, and the Manifest fragment `META-INF/wow/manifest/<module>.json`; and gives the route `POST /catalog/product/{id}/change-price`.
6. Says the exact coordinates, versions and symbols must be verified against the pinned Wow version, and reports the narrowest check it would run (the domain module's tests) without claiming to have run it.
