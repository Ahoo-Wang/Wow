# Handler Discovery

Load this only when a command, sourcing, Saga, Projection, or EventProcessor handler is missing, unregistered, unmatched, or unselected.

## Discovery chain

First prove the source method and its annotation or naming convention. Then select
the discovery path that the current handler type actually uses:

- **Aggregate route and function path**: inspect the KSP-generated
  `META-INF/wow-metadata.json` for bounded-context, aggregate, command, and event
  mappings, then inspect `AggregateMetadataParser` and
  `StateAggregateMetadataParser` for runtime command and sourcing function selection.
- **Spring processor path**: for Saga, Projection, and EventProcessor beans,
  trace annotated-bean discovery through `AutoRegistrar`, the type-specific runtime
  metadata parser, and the function registrar. Processor functions are not
  registered from generated processor metadata; however, verify generated
  bounded-context scope metadata when `requiredNamedBoundedContext()` cannot resolve.
- **Explicit registration path**: when application code registers a
  `MessageFunction` or processor directly, trace that call and the resulting
  function set; determine separately whether metadata construction requires
  bounded-context resolution.

After registration, prove subscription scope and message delivery, function kind,
event/body type and aggregate/topic selection, invocation, then the handler result
or exception. Stop at the first incorrect transition.

```bash
rg -n "@AggregateRoot|@OnCommand|@OnSourcing|@StatelessSaga|@ProjectionProcessor|@EventProcessor|@OnEvent|@OnStateEvent" . -g '*.kt' -g '*.java'
rg -n "wow-metadata|META-INF|SymbolProcessor|AutoRegistrar|FunctionRegistrar|MetadataParser" . -g '*.kt' -g '*.java' -g '*.json'
```

Resolve exact resource names and schemas only after current source proves that the
selected stage uses generated metadata. A compiling annotation proves neither
aggregate route metadata nor runtime processor bean discovery.

## Distinguish selection from execution

- No invocation: inspect metadata, registration, subscription, scope, and filter selection.
- Invocation followed by failure: inspect parameter resolution, handler result, scheduler, exception, retry, and compensation.
- Direct method test passes but runtime misses it: focus on the discovery/registration chain, not handler logic.
- Retry cannot explain a handler that was never selected.

When inspecting packaged artifacts, use a temporary directory and read-only extraction/listing. Do not modify generated resources by hand.
