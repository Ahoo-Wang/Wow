# Command Delivery Decisions

Use this reference for `CommandGateway`, command buses, waiting, idempotency, HTTP command routes, delivery ambiguity, and propagation.

## Separate the concerns

- The command body expresses intent; routing identifies the target aggregate and context.
- The bus delivers commands; the gateway may add request/response and waiting behavior.
- Acceptance, execution, event persistence, projection completion, and client observation are different stages.
- A timeout can mean an unknown outcome. Do not automatically retry a non-idempotent command after an ambiguous result.
- Treat command IDs, wait plans, propagated headers, and idempotency keys as one end-to-end contract.

## Discover the target version

```bash
rg -n "CommandGateway|CommandBus|sendAndWait|WaitPlan|CommandWait|WaitingChain" . -g '*.kt' -g '*.java'
rg -n "Command-Wait|CommandRoute|AggregateRoute" . -g '*.kt' -g '*.java'
```

In this repository, start with:

- `wow-core/src/main/kotlin/me/ahoo/wow/command/`
- `wow-core/src/main/kotlin/me/ahoo/wow/command/wait/`
- `wow-core/src/main/kotlin/me/ahoo/wow/messaging/propagation/`
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/`
- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/command/`

Read the interface, implementation, timeout/cancellation tests, propagators, WebFlux parsing, and OpenAPI generation together. Do not preserve a method, header, stage, or default from memory.

## Design and test

Before changing delivery behavior, identify:

1. who owns the deadline;
2. which stage completes the caller;
3. how cancellation disposes wait resources;
4. whether a timeout proves failure or only missing observation;
5. how retries preserve command identity and idempotency;
6. which headers/context must cross process boundaries;
7. how HTTP/OpenAPI contracts expose the behavior.

Use focused unit tests for plan construction and propagation, gateway tests for lifecycle/timeout cleanup, and transport tests for HTTP parsing and generated contracts. Add integration coverage when the behavior crosses a real bus or processor boundary.
