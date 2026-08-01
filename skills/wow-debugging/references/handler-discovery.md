# Handler Discovery and Selection

Load this reference only when a command, sourcing function, Saga, projection, or event processor is missing, unregistered, unmatched, or never selected. First identify the applicable discovery path; do not assume every handler uses KSP metadata.

## Aggregate Metadata Resource Chain

`@BoundedContext` and `@AggregateRoot` metadata is generated into `META-INF/wow-metadata.json`. Resolve the real Gradle module from `settings.gradle.kts`, then verify generation, resource copying, packaging, and runtime classpath in order:

```bash
rg -n 'com.google.devtools.ksp|ksp\(.*wow-compiler' <module>/build.gradle.kts
./gradlew :<module>:kspKotlin --info
test -f <module>/build/generated/ksp/main/resources/META-INF/wow-metadata.json
jq '.contexts' <module>/build/generated/ksp/main/resources/META-INF/wow-metadata.json
./gradlew :<module>:processResources :<module>:jar
test -f <module>/build/resources/main/META-INF/wow-metadata.json
jar tf <actual-module-jar> | rg '^META-INF/wow-metadata.json$'
```

Locate `<actual-module-jar>` under that module's `build/libs/`; do not guess its versioned filename. Runtime aggregate metadata loads and merges classpath resources with this exact name, so also verify that the producer module is on the failing application's runtime classpath.

For a missing sourcing handler, inspect the current `StateAggregateMetadataParser` rules. A metadata resource proves bounded-context and aggregate discovery, not selection of an individual function.

## Spring Processor Registration

`META-INF/wow-metadata.json` does not register `@StatelessSaga`, `@ProjectionProcessor`, or `@EventProcessor` beans. It can still affect their default topic resolution: when `@OnEvent` or `@OnStateEvent` does not name an aggregate explicitly, `FunctionMetadataParser` derives `supportedTopics` from the event body's `namedAggregate()` metadata. Missing producer metadata can therefore leave the processor registered but unsubscribed. Prove each stage separately:

1. Spring component scanning includes the processor package.
2. The annotated bean exists in the `ApplicationContext`.
3. The corresponding `AutoRegistrar` runs and registers its function metadata.
4. `FunctionMetadataParser` resolves non-empty `supportedTopics`, either from explicit aggregate names or the event body's aggregate metadata.
5. The dispatcher builds a bus subscription from those supported topics.
6. After delivery, the registrar and dispatcher select a function compatible with the event body, topic, and compensation headers where applicable.

Use current sources as the authority:

- `wow-spring/src/main/kotlin/me/ahoo/wow/spring/AutoRegistrar.kt`
- `wow-spring/src/main/kotlin/me/ahoo/wow/spring/saga/StatelessSagaProcessorAutoRegistrar.kt`
- `wow-spring/src/main/kotlin/me/ahoo/wow/spring/projection/ProjectionProcessorAutoRegistrar.kt`
- `wow-spring/src/main/kotlin/me/ahoo/wow/spring/event/EventProcessorAutoRegistrar.kt`
- `wow-core/src/main/kotlin/me/ahoo/wow/saga/stateless/StatelessSagaFunctionRegistrar.kt`
- `wow-core/src/main/kotlin/me/ahoo/wow/messaging/function/FunctionMetadataParser.kt`

## Selection, Delivery, and Invocation

Keep these boundaries distinct:

```text
bean discovery -> function registration -> supportedTopics resolution -> bus subscription -> delivery
               -> body/topic match -> setFunction -> filter chain/invocation
```

If the dispatcher finds no compatible function, investigate registration, event body type, context/aggregate matching, routing, and subscription before changing handler code.

`@Retry` is metadata on a function already selected and attached to the exchange. `EventCompensationFilter` can inspect it for a downstream reactive failure even when parameter injection or another failure occurs before the handler body begins. It cannot explain a function that was never registered, matched, or selected, or an event that never reached the dispatcher.

Verify this boundary in:

- `wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/AbstractAggregateEventDispatcher.kt`
- `wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/AbstractEventDispatcher.kt`
- `wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/DomainEventFunctionFilter.kt`
- `wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/MainDispatcher.kt`
- `compensation/wow-compensation-core/src/main/kotlin/me/ahoo/wow/compensation/core/CompensationFilter.kt`
