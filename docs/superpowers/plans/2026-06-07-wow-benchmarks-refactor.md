# wow-benchmarks Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `wow-benchmarks` into a clearer JMH benchmark suite with Smoke, Local Runtime, and Infrastructure I/O execution paths.

**Architecture:** Keep `wow-benchmarks` as one Gradle module and reorganize only module-private benchmark code. Extract lifecycle-free fixture objects under `me.ahoo.wow.benchmark.fixture`, stateful runtime scenarios under `me.ahoo.wow.benchmark.scenario`, and Redis/Mongo service-facing code under `me.ahoo.wow.infrastructure`. Gradle reporting remains under `wow-benchmarks/gradle/` with renamed result files and group names.

**Tech Stack:** Kotlin 2.3.20, Gradle Kotlin DSL, JMH Gradle plugin, Reactor, Wow command/event sourcing runtime, Redis reactive template, Mongo reactive streams, Groovy `JsonSlurper` for Gradle report parsing.

---

## Scope Check

The approved spec covers one Gradle module and three tightly coupled concerns inside that module: JMH task taxonomy, benchmark fixture/scenario code, and report/baseline task behavior. This can be implemented as one plan because every task produces a compiling `wow-benchmarks` module and the validation ladder stays module-scoped.

## File Structure

Create:

- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkAggregates.kt`: cart aggregate metadata, named aggregate, and aggregate id helpers.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkIds.kt`: deterministic benchmark global id generator setup and generated id helper.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkCommands.kt`: fixed-aggregate, new-aggregate, and smoke `AddCartItem` command messages.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkEvents.kt`: single-event and multi-event `DomainEventStream` factories.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkHeaders.kt`: header factory for hot path benchmarks.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkIdempotency.kt`: Bloom filter idempotency checker factory.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/BenchmarkErrorHandling.kt`: `Blackhole` helper for benchmark methods that intentionally consume `WowException`.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandGatewayScenario.kt`: lifecycle wrapper for `DefaultCommandGateway`.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandPipelineScenario.kt`: reusable local command handler chain variants.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandDispatcherScenario.kt`: reusable command dispatcher scenario for local, Redis, and Mongo event stores.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/InfrastructureAvailability.kt`: Redis/Mongo socket probes and Gradle-friendly failure messages.

Modify:

- `wow-benchmarks/build.gradle.kts`: rename benchmark tasks and result paths.
- `wow-benchmarks/gradle/benchmark-reporting.gradle.kts`: rename grouped reports, local result parsing, baseline comparison, and update commands.
- `wow-benchmarks/README.md`: update stable command instructions after generator changes.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/*.kt`: consume fixtures/scenarios and remove command fixture duplication.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/*.kt`: consume event fixtures and scenario helpers.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/*.kt`: consume fixtures/scenarios and keep only fine-grained hot path benchmark logic.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/modeling/*.kt`: migrate dispatcher setup to `CommandDispatcherScenario`.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/*.kt`: move benchmark classes to `me.ahoo.wow.infrastructure.redis` packages.
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/*.kt`: move benchmark classes to `me.ahoo.wow.infrastructure.mongo` packages.

Delete after migration:

- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt`
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/Events.kt`
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt`

## Task 1: Rename Gradle Benchmark Taxonomy

**Files:**
- Modify: `wow-benchmarks/build.gradle.kts`
- Modify: `wow-benchmarks/gradle/benchmark-reporting.gradle.kts`
- Modify: `wow-benchmarks/README.md`

- [ ] **Step 1: Verify the old task names are the current behavior**

Run:

```bash
./gradlew :wow-benchmarks:tasks --all | rg "benchmarkInternal|benchmarkExternal|benchmarkSmoke"
```

Expected: output contains `benchmarkInternal`, `benchmarkExternal`, and `benchmarkSmoke`.

- [ ] **Step 2: Replace benchmark result providers and custom run tasks**

In `wow-benchmarks/build.gradle.kts`, replace the existing `benchmarkInternalReport`, `benchmarkExternalReport`, `benchmarkInternalHumanReport`, `benchmarkExternalHumanReport`, `configureJmhBenchmarkRun`, `benchmarkInternal`, and `benchmarkExternal` section with this block:

```kotlin
val benchmarkLocalReport = layout.buildDirectory.file("results/jmh/local.json")
val benchmarkInfrastructureReport = layout.buildDirectory.file("results/jmh/infrastructure.json")
val benchmarkLocalHumanReport = layout.buildDirectory.file("reports/jmh/local-human.txt")
val benchmarkInfrastructureHumanReport = layout.buildDirectory.file("reports/jmh/infrastructure-human.txt")

val benchmarkJvmArgs = listOf(
    "-Xmx4g",
    "-Xms4g",
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+DebugNonSafepoints",
    "-XX:+AlwaysPreTouch",
)

fun benchmarkProfilerArgs(): List<String> {
    val asyncProfilerLib = file("/opt/async-profiler/lib/libasyncProfiler.dylib")
    return buildList {
        add("-prof")
        add("gc")
        add("-prof")
        if (asyncProfilerLib.exists()) {
            add("async:output=flamegraph;dir=build/profiling;event=cpu;libPath=${asyncProfilerLib.absolutePath}")
        } else {
            add("stack:lines=10;top=20")
        }
    }
}

fun JavaExec.configureJmhBenchmarkRun(
    includePattern: String,
    resultsFile: Provider<RegularFile>,
    humanOutputFile: Provider<RegularFile>,
) {
    dependsOn(tasks.named("jmhJar"))
    classpath(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    args(
        includePattern,
        "-t",
        "1",
        "-wi",
        "2",
        "-w",
        "5s",
        "-i",
        "3",
        "-r",
        "10s",
        "-f",
        "2",
        "-foe",
        "true",
        "-rf",
        "json",
        "-rff",
        resultsFile.get().asFile.absolutePath,
        "-o",
        humanOutputFile.get().asFile.absolutePath,
        "-jvmArgs",
        benchmarkJvmArgs.joinToString(" "),
    )
    args(benchmarkProfilerArgs())
    outputs.file(resultsFile)
    outputs.file(humanOutputFile)
    outputs.upToDateWhen { false }
    doFirst {
        resultsFile.get().asFile.parentFile.mkdirs()
        humanOutputFile.get().asFile.parentFile.mkdirs()
    }
}

tasks.register<JavaExec>("benchmarkLocal") {
    description = "Runs local JVM, Noop, and InMemory JMH benchmarks without Redis or Mongo."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = """me\.ahoo\.wow\.(?!infrastructure\.|mongo\.|redis\.).*Benchmark.*""",
        resultsFile = benchmarkLocalReport,
        humanOutputFile = benchmarkLocalHumanReport,
    )
}

tasks.register<JavaExec>("benchmarkInfrastructure") {
    description = "Runs Redis and Mongo infrastructure I/O JMH benchmarks."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = """me\.ahoo\.wow\.(infrastructure\.|mongo\.|redis\.).*Benchmark.*""",
        resultsFile = benchmarkInfrastructureReport,
        humanOutputFile = benchmarkInfrastructureHumanReport,
    )
}
```

- [ ] **Step 3: Update the default JMH result file to local naming**

In the existing `jmh` extension block in `wow-benchmarks/build.gradle.kts`, replace:

```kotlin
resultsFile.set(layout.buildDirectory.file("results/jmh/latest.json"))
```

with:

```kotlin
resultsFile.set(layout.buildDirectory.file("results/jmh/local.json"))
```

Keep `humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))` unchanged in this task.

- [ ] **Step 4: Rename reporting providers**

In `wow-benchmarks/gradle/benchmark-reporting.gradle.kts`, replace the top-level result providers with:

```kotlin
val resultsDir = layout.projectDirectory.dir("results")
val baselineJson = resultsDir.file("baseline.json")
val localJson = layout.buildDirectory.file("results/jmh/local.json")
val readmeFile = layout.projectDirectory.file("README.md")

val benchmarkLocalReport = layout.buildDirectory.file("results/jmh/local.json")
val benchmarkInfrastructureReport = layout.buildDirectory.file("results/jmh/infrastructure.json")
```

Then replace every current `latestJson` reference with `localJson`, `benchmarkInternalReport` with `benchmarkLocalReport`, and `benchmarkExternalReport` with `benchmarkInfrastructureReport`.

- [ ] **Step 5: Rename grouped report labels and commands**

In `generateGroupedBenchmarkReport`, replace the current `groups` argument passed to `renderGroupedBenchmarkReport` with:

```kotlin
groups = listOf(
    BenchmarkResultGroup(
        name = "Local Runtime",
        command = "./gradlew :wow-benchmarks:benchmarkLocal",
        resultFile = benchmarkLocalReport,
    ),
    BenchmarkResultGroup(
        name = "Infrastructure I/O",
        command = "./gradlew :wow-benchmarks:benchmarkInfrastructure",
        resultFile = benchmarkInfrastructureReport,
        required = false,
    ),
)
```

- [ ] **Step 6: Update report/baseline task messages**

In `generateBenchmarkReport`, remove this dependency line:

```kotlin
dependsOn(tasks.named("jmh"))
```

Then in `generateBenchmarkReport`, `benchmarkCompare`, and `updateBaseline`, use these exact failure messages:

```kotlin
throw GradleException("Local JMH results not found: ${resultsFile.absolutePath}. Run :wow-benchmarks:benchmarkLocal first.")
```

```kotlin
throw GradleException("Local benchmark results not found: ${localFile.absolutePath}. Run :wow-benchmarks:benchmarkLocal first.")
```

```kotlin
throw GradleException("Local benchmark results not found: ${localFile.absolutePath}. Run :wow-benchmarks:benchmarkLocal first.")
```

Use `val localFile = localJson.get().asFile` in `benchmarkCompare` and `updateBaseline` before these checks.

- [ ] **Step 7: Update stable README command instructions**

In `wow-benchmarks/README.md`, replace the current command block with:

```markdown
> Run `./gradlew :wow-benchmarks:benchmarkLocal :wow-benchmarks:generateBenchmarkReport` to generate the latest local runtime report.
>
> Run infrastructure benchmarks: `./gradlew :wow-benchmarks:benchmarkInfrastructure :wow-benchmarks:generateGroupedBenchmarkReport`
>
> Compare against baseline: `./gradlew :wow-benchmarks:benchmarkCompare`
>
> Update baseline: `./gradlew :wow-benchmarks:updateBaseline`
```

- [ ] **Step 8: Verify task names**

Run:

```bash
./gradlew :wow-benchmarks:tasks --all | rg "benchmarkSmoke|benchmarkLocal|benchmarkInfrastructure"
```

Expected: output contains `benchmarkSmoke`, `benchmarkLocal`, and `benchmarkInfrastructure`.

- [ ] **Step 9: Verify old task names are gone**

Run:

```bash
./gradlew :wow-benchmarks:tasks --all | rg "benchmarkInternal|benchmarkExternal"
```

Expected: command exits with status `1` and prints no matches.

- [ ] **Step 10: Compile the Gradle task graph**

Run:

```bash
./gradlew :wow-benchmarks:help --task benchmarkLocal :wow-benchmarks:help --task benchmarkInfrastructure
```

Expected: both task help sections render and the build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit the task rename**

Run:

```bash
git add wow-benchmarks/build.gradle.kts wow-benchmarks/gradle/benchmark-reporting.gradle.kts wow-benchmarks/README.md
git commit -m "refactor(benchmarks): rename benchmark run groups"
```

## Task 2: Extract Lifecycle-Free Benchmark Fixtures

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkAggregates.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkIds.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkCommands.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkEvents.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkHeaders.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkIdempotency.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/CommandFactoryBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/BloomFilterIdempotencyCheckerBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/GlobalIdBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HeaderCreationBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/MessageWrappingBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateIdGenerationBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/EventPublishBenchmark.kt`

- [ ] **Step 1: Verify duplicated fixture references before extraction**

Run:

```bash
rg -n "HotPathFixture|createSmokeCommandMessage|createBloomFilterIdempotencyChecker|cartAggregateMetadata|createEventStream" wow-benchmarks/src/jmh/kotlin
```

Expected: output includes matches in `command/Commands.kt`, `hotpath/HotPathFixture.kt`, `eventsourcing/Events.kt`, and benchmark classes.

- [ ] **Step 2: Create aggregate fixture**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkAggregates.kt` with the standard Apache header used by existing benchmark files and this body:

```kotlin
package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.aggregateId

object BenchmarkAggregates {
    val namedAggregate = MaterializedNamedAggregate("example-service", "cart")
    val cartMetadata by lazy { aggregateMetadata<Cart, CartState>() }
    const val FIXED_AGGREGATE_ID: String = "benchmark-cart-fixed-id"

    fun aggregateId(): AggregateId {
        return cartMetadata.aggregateId()
    }
}
```

- [ ] **Step 3: Create id fixture**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkIds.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.fixture

import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.wow.id.CosIdGlobalIdGeneratorFactory
import me.ahoo.wow.id.generateGlobalId

object BenchmarkIds {
    fun installDeterministicGlobalIdGenerator() {
        DefaultIdGeneratorProvider.INSTANCE.set(
            CosIdGlobalIdGeneratorFactory.ID_NAME,
            ClockSyncCosIdGenerator(Radix62CosIdGenerator(0)),
        )
    }

    fun nextGlobalId(): String {
        installDeterministicGlobalIdGenerator()
        return generateGlobalId()
    }
}
```

- [ ] **Step 4: Create command fixture**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkCommands.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.modeling.MaterializedNamedAggregate

object BenchmarkCommands {
    fun fixedAggregateAddCartItem(): CommandMessage<AddCartItem> {
        val id = BenchmarkIds.nextGlobalId()
        return addCartItem(
            id = id,
            requestId = id,
            aggregateId = BenchmarkAggregates.FIXED_AGGREGATE_ID,
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )
    }

    fun newAggregateAddCartItem(): CommandMessage<AddCartItem> {
        val id = BenchmarkIds.nextGlobalId()
        return addCartItem(
            id = id,
            requestId = id,
            aggregateId = BenchmarkIds.nextGlobalId(),
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )
    }

    fun smokeAddCartItem(): CommandMessage<AddCartItem> {
        return addCartItem(
            id = "benchmark-command-id",
            requestId = "benchmark-request-id",
            aggregateId = "benchmark-cart-id",
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )
    }

    private fun addCartItem(
        id: String,
        requestId: String?,
        aggregateId: String?,
        namedAggregate: MaterializedNamedAggregate?,
    ): CommandMessage<AddCartItem> {
        return AddCartItem(productId = "productId").toCommandMessage(
            id = id,
            requestId = requestId,
            aggregateId = aggregateId,
            namedAggregate = namedAggregate,
        )
    }
}
```

- [ ] **Step 5: Create event fixture**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkEvents.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.test.aggregate.GivenInitializationCommand

object BenchmarkEvents {
    fun singleEventStream(
        aggregateId: AggregateId = BenchmarkAggregates.aggregateId(),
        aggregateVersion: Int = 0,
    ): DomainEventStream {
        val event = CartItemAdded(CartItem("productId"))
        return listOf<Any>(event).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = aggregateVersion,
        )
    }

    fun singleBodyEventStream(
        aggregateId: AggregateId = BenchmarkAggregates.aggregateId(),
    ): DomainEventStream {
        val event = CartItemAdded(CartItem("productId"))
        return event.toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
        )
    }

    fun eventStreams(
        aggregateId: AggregateId = BenchmarkAggregates.aggregateId(),
        eventCount: Int,
    ): List<DomainEventStream> {
        return (1..eventCount).map { version ->
            val event = CartItemAdded(CartItem("product-$version", version))
            listOf<Any>(event).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = version - 1,
            )
        }
    }
}
```

- [ ] **Step 6: Create header fixture**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkHeaders.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.messaging.DefaultHeader

object BenchmarkHeaders {
    fun emptyHeader(): Header {
        return DefaultHeader()
    }
}
```

- [ ] **Step 7: Create idempotency fixture**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture/BenchmarkIdempotency.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.fixture

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import java.time.Duration

object BenchmarkIdempotency {
    fun bloomFilterChecker(): BloomFilterIdempotencyChecker {
        return BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8),
                10_000_000,
                0.00001,
            )
        }
    }
}
```

- [ ] **Step 8: Migrate simple command benchmarks**

Apply these exact replacements:

```kotlin
// CommandFactoryBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands

val commandMessage = BenchmarkCommands.smokeAddCartItem()
```

```kotlin
// BloomFilterIdempotencyCheckerBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.benchmark.fixture.BenchmarkIds

idempotencyChecker = BenchmarkIdempotency.bloomFilterChecker()
val result = idempotencyChecker.check(BenchmarkIds.nextGlobalId()).block()
```

```kotlin
// GlobalIdBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkIds

init {
    BenchmarkIds.installDeterministicGlobalIdGenerator()
}

@Benchmark
fun generateId(blackhole: Blackhole) {
    val id = BenchmarkIds.nextGlobalId()
    blackhole.consume(id)
}
```

- [ ] **Step 9: Migrate hot path fixture consumers**

Apply these replacements:

```kotlin
// HeaderCreationBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkHeaders

private val fixture = BenchmarkHeaders
val header = fixture.emptyHeader()
```

```kotlin
// MessageWrappingBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands

val msg = BenchmarkCommands.newAggregateAddCartItem()
```

```kotlin
// AggregateIdGenerationBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIds

val id = BenchmarkIds.nextGlobalId()
namedAggregate = BenchmarkAggregates.namedAggregate
```

```kotlin
// EventPublishBenchmark.kt
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents

private val eventStream = BenchmarkEvents.singleEventStream()
eventBus.receive(setOf(BenchmarkAggregates.namedAggregate)).subscribe()
```

- [ ] **Step 10: Compile JMH Kotlin after fixture extraction**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit lifecycle-free fixtures**

Run:

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/fixture \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/CommandFactoryBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/BloomFilterIdempotencyCheckerBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/GlobalIdBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HeaderCreationBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/MessageWrappingBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateIdGenerationBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/EventPublishBenchmark.kt
git commit -m "refactor(benchmarks): extract shared benchmark fixtures"
```

## Task 3: Extract Runtime Scenarios

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/BenchmarkErrorHandling.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandGatewayScenario.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandPipelineScenario.kt`
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandDispatcherScenario.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/CommandGatewayBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/InMemoryCommandBusBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/modeling/AbstractCommandDispatcherBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandProcessingPipelineBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisCommandProcessingPipelineBenchmark.kt`

- [ ] **Step 1: Create benchmark error helper**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/BenchmarkErrorHandling.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.exception.WowException
import org.openjdk.jmh.infra.Blackhole

inline fun Blackhole.consumeWowResult(block: () -> Any?) {
    try {
        consume(block())
    } catch (wowException: WowException) {
        consume(wowException)
    }
}
```

- [ ] **Step 2: Create command gateway scenario**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandGatewayScenario.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.scenario

import jakarta.validation.Validator
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.test.validation.TestValidator
import reactor.core.Disposable

class CommandGatewayScenario private constructor(
    val commandBus: CommandBus,
    val commandGateway: CommandGateway,
    private val subscription: Disposable?,
) : AutoCloseable {
    override fun close() {
        subscription?.dispose()
        commandGateway.close()
    }

    companion object {
        fun inMemory(
            validator: Validator = TestValidator,
            idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
                DefaultAggregateIdempotencyCheckerProvider {
                    NoOpIdempotencyChecker
                },
            subscribeCart: Boolean = true,
        ): CommandGatewayScenario {
            val commandBus = InMemoryCommandBus()
            val waitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
            val commandGateway = DefaultCommandGateway(
                commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
                commandBus = commandBus,
                validator = validator,
                idempotencyCheckerProvider = idempotencyCheckerProvider,
                waitStrategyRegistrar = SimpleWaitStrategyRegistrar,
                commandWaitNotifier = waitNotifier,
            )
            val subscription = if (subscribeCart) {
                commandGateway
                    .receive(setOf(BenchmarkAggregates.cartMetadata.namedAggregate.materialize()))
                    .subscribe()
            } else {
                null
            }
            return CommandGatewayScenario(commandBus, commandGateway, subscription)
        }

        fun inMemoryWithBloomFilterIdempotency(): CommandGatewayScenario =
            inMemory(
                idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                    BenchmarkIdempotency.bloomFilterChecker()
                },
            )
    }
}
```

- [ ] **Step 3: Create command pipeline scenario**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandPipelineScenario.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory

class CommandPipelineScenario(
    eventStore: EventStore = NoopEventStore,
    domainEventBus: DomainEventBus = InMemoryDomainEventBus(),
    private val commandWaitNotifier: CommandWaitNotifier =
        LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar),
) {
    val aggregateOnlyHandler: CommandHandler
    val aggregateDomainAndStateEventHandler: CommandHandler
    val aggregateDomainStateAndProcessedNotifierHandler: CommandHandler

    init {
        val stateAggregateRepository = EventSourcingStateAggregateRepository(
            ConstructorStateAggregateFactory,
            InMemorySnapshotRepository(),
            eventStore,
        )
        val aggregateProcessorFactory = RetryableAggregateProcessorFactory(
            ConstructorStateAggregateFactory,
            stateAggregateRepository,
            SimpleCommandAggregateFactory(eventStore),
        )
        val aggregateProcessorFilter = AggregateProcessorFilter(
            serviceProvider = SimpleServiceProvider(),
            aggregateProcessorFactory = aggregateProcessorFactory,
        )
        aggregateOnlyHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(aggregateProcessorFilter)
                .build(),
        )
        aggregateDomainAndStateEventHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(aggregateProcessorFilter)
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .addFilter(SendStateEventFilter(InMemoryStateEventBus()))
                .build(),
        )
        aggregateDomainStateAndProcessedNotifierHandler = DefaultCommandHandler(
            FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(aggregateProcessorFilter)
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .addFilter(SendStateEventFilter(InMemoryStateEventBus()))
                .addFilter(ProcessedNotifierFilter(commandWaitNotifier))
                .build(),
        )
    }

    fun createServerExchange(newAggregate: Boolean = true): ServerCommandExchange<*> {
        val message = if (newAggregate) {
            BenchmarkCommands.newAggregateAddCartItem()
        } else {
            BenchmarkCommands.fixedAggregateAddCartItem()
        }
        val exchange = SimpleServerCommandExchange(message)
        exchange.setAggregateMetadata(BenchmarkAggregates.cartMetadata)
        return exchange
    }
}
```

- [ ] **Step 4: Create command dispatcher scenario**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario/CommandDispatcherScenario.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.test.validation.TestValidator

class CommandDispatcherScenario private constructor(
    val gatewayScenario: CommandGatewayScenario,
    val commandDispatcher: CommandDispatcher,
) : AutoCloseable {
    val commandGateway: CommandGateway
        get() = gatewayScenario.commandGateway

    override fun close() {
        commandDispatcher.stop()
        gatewayScenario.close()
    }

    companion object {
        fun create(
            eventStore: EventStore = InMemoryEventStore(),
            snapshotRepository: SnapshotRepository = InMemorySnapshotRepository(),
            domainEventBus: DomainEventBus = InMemoryDomainEventBus(),
            stateEventBus: StateEventBus = InMemoryStateEventBus(),
            schedulerSupplier: AggregateSchedulerSupplier = BenchmarkAggregateSchedulerSupplier(),
            idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
                DefaultAggregateIdempotencyCheckerProvider {
                    BenchmarkIdempotency.bloomFilterChecker()
                },
        ): CommandDispatcherScenario {
            val gatewayScenario = CommandGatewayScenario.inMemory(
                validator = TestValidator,
                idempotencyCheckerProvider = idempotencyCheckerProvider,
                subscribeCart = false,
            )
            val stateAggregateRepository: StateAggregateRepository =
                EventSourcingStateAggregateRepository(
                    ConstructorStateAggregateFactory,
                    snapshotRepository,
                    eventStore,
                )
            val aggregateProcessorFactory = RetryableAggregateProcessorFactory(
                ConstructorStateAggregateFactory,
                stateAggregateRepository,
                SimpleCommandAggregateFactory(eventStore),
            )
            val chain = FilterChainBuilder<ServerCommandExchange<*>>()
                .addFilter(AggregateProcessorFilter(SimpleServiceProvider(), aggregateProcessorFactory))
                .addFilter(SendDomainEventStreamFilter(domainEventBus))
                .addFilter(SendStateEventFilter(stateEventBus))
                .addFilter(ProcessedNotifierFilter(LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)))
                .build()
            val dispatcher = CommandDispatcher(
                namedAggregates = setOf(BenchmarkAggregates.cartMetadata.namedAggregate),
                commandBus = gatewayScenario.commandGateway,
                commandHandler = DefaultCommandHandler(chain),
                schedulerSupplier = schedulerSupplier,
            )
            dispatcher.start()
            return CommandDispatcherScenario(gatewayScenario, dispatcher)
        }
    }
}
```

- [ ] **Step 5: Migrate `CommandGatewayBenchmark` to `CommandGatewayScenario`**

Replace the setup/tearDown fields in `CommandGatewayBenchmark.kt` with:

```kotlin
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandGatewayScenario

private lateinit var scenario: CommandGatewayScenario

@Setup
fun setup() {
    scenario = CommandGatewayScenario.inMemory()
}

@TearDown
fun tearDown() {
    scenario.close()
}

@Benchmark
fun send() {
    scenario.commandGateway.send(BenchmarkCommands.fixedAggregateAddCartItem()).block()
}
```

Remove now-unused imports from the file.

- [ ] **Step 6: Migrate `InMemoryCommandBusBenchmark` to fixtures only**

Replace command creation and aggregate metadata references in `InMemoryCommandBusBenchmark.kt` with:

```kotlin
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands

commandBus.receive(setOf(BenchmarkAggregates.cartMetadata.namedAggregate.materialize())).subscribe()
val commandMessage = BenchmarkCommands.fixedAggregateAddCartItem()
```

- [ ] **Step 7: Migrate `AbstractCommandDispatcherBenchmark` to scenario wrapper**

Replace the private fields and `setup()`/`destroy()` body in `AbstractCommandDispatcherBenchmark.kt` with:

```kotlin
private lateinit var scenario: CommandDispatcherScenario

open fun setup() {
    scenario = CommandDispatcherScenario.create(
        eventStore = createEventStore(),
        snapshotRepository = createSnapshotRepository(),
        domainEventBus = createDomainEventBus(),
        stateEventBus = createStateEventBus(),
        schedulerSupplier = createSchedulerSupplier(),
        idempotencyCheckerProvider = createIdempotencyCheckerProvider(),
    )
}

open fun destroy() {
    scenario.close()
}
```

Then update send helpers to use `scenario.commandGateway`:

```kotlin
open fun send(blackHole: Blackhole) {
    run(blackHole) {
        scenario.commandGateway.send(createBenchmarkCommandMessage()).block()
    }
}

open fun sendAndWaitForSent(blackHole: Blackhole) {
    run(blackHole) {
        scenario.commandGateway.sendAndWaitForSent(createBenchmarkCommandMessage()).block()
    }
}

open fun sendAndWaitForProcessed(blackHole: Blackhole) {
    run(blackHole) {
        scenario.commandGateway.sendAndWaitForProcessed(createBenchmarkCommandMessage()).block()
    }
}
```

Use these imports:

```kotlin
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
```

Update `createBenchmarkCommandMessage()` to:

```kotlin
open fun createBenchmarkCommandMessage(): CommandMessage<AddCartItem> {
    return BenchmarkCommands.fixedAggregateAddCartItem()
}
```

- [ ] **Step 8: Migrate command pipeline benchmarks to `CommandPipelineScenario`**

In `hotpath/CommandProcessingPipelineBenchmark.kt`, replace the handler fields with:

```kotlin
private lateinit var pipelineScenario: CommandPipelineScenario
private lateinit var dispatcherScenario: CommandDispatcherScenario
```

Replace setup and teardown with:

```kotlin
@Setup
fun setup() {
    pipelineScenario = CommandPipelineScenario()
    dispatcherScenario = CommandDispatcherScenario.create(eventStore = NoopEventStore)
}

@TearDown
fun tearDown() {
    dispatcherScenario.close()
}
```

Replace handler calls:

```kotlin
pipelineScenario.aggregateOnlyHandler.handle(pipelineScenario.createServerExchange()).block()
pipelineScenario.aggregateDomainAndStateEventHandler.handle(pipelineScenario.createServerExchange()).block()
pipelineScenario.aggregateDomainStateAndProcessedNotifierHandler.handle(pipelineScenario.createServerExchange()).block()
```

Replace gateway calls:

```kotlin
dispatcherScenario.commandGateway.sendAndWaitForProcessed(
    BenchmarkCommands.newAggregateAddCartItem(),
).block()
```

Keep the direct aggregate processor comparison in this file only if the benchmark still measures retry overhead. If it stays, move the direct factory classes to a private file-local section at the bottom and name the benchmark `handleAggregateOnlyWithoutRetry`.

- [ ] **Step 9: Compile after scenario extraction**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit runtime scenarios**

Run:

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/scenario \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/CommandGatewayBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/InMemoryCommandBusBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/modeling/AbstractCommandDispatcherBenchmark.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandProcessingPipelineBenchmark.kt
git commit -m "refactor(benchmarks): extract runtime benchmark scenarios"
```

## Task 4: Isolate Infrastructure Benchmarks

**Files:**
- Create: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/InfrastructureAvailability.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisBenchmarkFixture.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisBenchmarkFixture.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisCommandDispatcherBenchmark.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisCommandDispatcherBenchmark.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisCommandProcessingPipelineBenchmark.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisCommandProcessingPipelineBenchmark.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisEventStoreBenchmark.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisEventStoreBenchmark.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisEventStoreReadBenchmark.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisEventStoreReadBenchmark.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/MongoInitializer.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo/MongoBenchmarkFixture.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/MongoCommandDispatcherBenchmark.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo/MongoCommandDispatcherBenchmark.kt`
- Move: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/MongoEventStoreBenchmark.kt` to `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo/MongoEventStoreBenchmark.kt`
- Modify: `wow-benchmarks/build.gradle.kts`

- [ ] **Step 1: Create infrastructure availability helper**

Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/InfrastructureAvailability.kt` with the standard Apache header and this body:

```kotlin
package me.ahoo.wow.infrastructure

import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

object InfrastructureAvailability {
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)

    fun requireRedis() {
        requirePort("Redis", "localhost", 6379, "./gradlew :wow-benchmarks:benchmarkInfrastructure")
    }

    fun requireMongo() {
        requirePort("MongoDB", "localhost", 27017, "./gradlew :wow-benchmarks:benchmarkInfrastructure")
    }

    private fun requirePort(service: String, host: String, port: Int, command: String) {
        val available = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT.toMillis().toInt())
            }
        }.isSuccess
        require(available) {
            "$service is required for Infrastructure I/O benchmarks at $host:$port. Start $service and rerun `$command`."
        }
    }
}
```

- [ ] **Step 2: Move Redis files with package rename**

Run:

```bash
mkdir -p wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisBenchmarkFixture.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisBenchmarkFixture.kt
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisCommandDispatcherBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisCommandDispatcherBenchmark.kt
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisCommandProcessingPipelineBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisCommandProcessingPipelineBenchmark.kt
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisEventStoreBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisEventStoreBenchmark.kt
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis/RedisEventStoreReadBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/redis/RedisEventStoreReadBenchmark.kt
```

In each moved Redis file, replace:

```kotlin
package me.ahoo.wow.redis
```

with:

```kotlin
package me.ahoo.wow.infrastructure.redis
```

Keep imports of production Redis classes such as `me.ahoo.wow.redis.eventsourcing.RedisEventStore`.

- [ ] **Step 3: Add Redis availability checks**

In `RedisBenchmarkFixture.kt`, add:

```kotlin
import me.ahoo.wow.infrastructure.InfrastructureAvailability
```

Then make the first line of `init`:

```kotlin
InfrastructureAvailability.requireRedis()
```

- [ ] **Step 4: Move Mongo files with package rename**

Run:

```bash
mkdir -p wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/MongoInitializer.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo/MongoBenchmarkFixture.kt
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/MongoCommandDispatcherBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo/MongoCommandDispatcherBenchmark.kt
git mv wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo/MongoEventStoreBenchmark.kt wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure/mongo/MongoEventStoreBenchmark.kt
```

In each moved Mongo file, replace:

```kotlin
package me.ahoo.wow.mongo
```

with:

```kotlin
package me.ahoo.wow.infrastructure.mongo
```

In `MongoBenchmarkFixture.kt`, rename the class:

```kotlin
class MongoBenchmarkFixture : AutoCloseable {
```

Update benchmark fields from `MongoInitializer` to `MongoBenchmarkFixture`.

- [ ] **Step 5: Add Mongo availability check and imports**

In `MongoBenchmarkFixture.kt`, add:

```kotlin
import me.ahoo.wow.infrastructure.InfrastructureAvailability
import me.ahoo.wow.mongo.EventStreamSchemaInitializer
```

Then make the first line of `init`:

```kotlin
InfrastructureAvailability.requireMongo()
```

- [ ] **Step 6: Migrate infrastructure benchmark fixture imports**

Use these replacements in moved infrastructure benchmark files:

```kotlin
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.CommandPipelineScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
```

Replace old command/event helpers:

```kotlin
BenchmarkCommands.newAggregateAddCartItem()
BenchmarkAggregates.cartMetadata
BenchmarkAggregates.aggregateId()
BenchmarkEvents.eventStreams(aggregateId = aggregateId, eventCount = eventCount)
```

- [ ] **Step 7: Narrow infrastructure include pattern**

After package moves compile locally, add these imports to the top of `wow-benchmarks/build.gradle.kts`:

```kotlin
import java.net.InetSocketAddress
import java.net.Socket
```

Add this helper near `benchmarkProfilerArgs()`:

```kotlin
fun requireBenchmarkService(service: String, port: Int) {
    val available = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("localhost", port), 2000)
        }
    }.isSuccess
    require(available) {
        "$service is required for Infrastructure I/O benchmarks at localhost:$port. " +
            "Start $service and rerun `./gradlew :wow-benchmarks:benchmarkInfrastructure`."
    }
}
```

Update `benchmarkInfrastructure` in `wow-benchmarks/build.gradle.kts` to:

```kotlin
includePattern = """me\.ahoo\.wow\.infrastructure\..*Benchmark.*"""
```

Add this preflight block inside the `benchmarkInfrastructure` task:

```kotlin
doFirst {
    requireBenchmarkService("Redis", 6379)
    requireBenchmarkService("MongoDB", 27017)
}
```

Update `benchmarkLocal` to:

```kotlin
includePattern = """me\.ahoo\.wow\.(?!infrastructure\.).*Benchmark.*"""
```

- [ ] **Step 8: Verify local task cannot match infrastructure packages**

Run:

```bash
./gradlew :wow-benchmarks:benchmarkLocal --dry-run
```

Expected: dry run completes without invoking Redis or Mongo setup code and ends with `BUILD SUCCESSFUL`.

- [ ] **Step 9: Compile after infrastructure move**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 10: Verify unavailable infrastructure message**

If Redis or Mongo is not running, run:

```bash
./gradlew :wow-benchmarks:benchmarkInfrastructure --stacktrace
```

Expected: build fails before long benchmark execution and the output contains either `Redis is required for Infrastructure I/O benchmarks` or `MongoDB is required for Infrastructure I/O benchmarks`.

If both services are running, stop one local service temporarily only if doing so is acceptable for this machine; otherwise record that this check was skipped because both infrastructure services were already available.

- [ ] **Step 11: Commit infrastructure isolation**

Run:

```bash
git add wow-benchmarks/build.gradle.kts \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infrastructure \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/redis \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/mongo
git commit -m "refactor(benchmarks): isolate infrastructure benchmarks"
```

## Task 5: Remove Legacy Fixture Files And Finish Benchmark Migration

**Files:**
- Delete: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt`
- Delete: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/Events.kt`
- Delete: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/event/EventDispatcherBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/AbstractEventStoreBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/AggregateStateRecoveryBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/EventStreamFactoryBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/SnapshotBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/AggregateLoadingBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandHandlingBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/CommandValidationBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/IdempotencyBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/SnapshotSaveBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/infra/DeepCopyBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/projection/ProjectionBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/saga/StatelessSagaBenchmark.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/serialization/SerializationBenchmark.kt`

- [ ] **Step 1: Find remaining legacy fixture references**

Run:

```bash
rg -n "HotPathFixture|createCommandMessageForNewAggregate\\(|createSmokeCommandMessage\\(|createBloomFilterIdempotencyChecker\\(|createSingleEventStream\\(|cartAggregateMetadata" wow-benchmarks/src/jmh/kotlin
rg -n "createCommandMessage\\(" wow-benchmarks/src/jmh/kotlin | rg -v "fun createCommandMessage"
rg -n "createEventStream\\(" wow-benchmarks/src/jmh/kotlin | rg -v "fun createEventStream"
```

Expected: output lists every remaining legacy helper consumer.

- [ ] **Step 2: Replace command helper references**

Use these exact replacements:

```kotlin
createCommandMessage() -> BenchmarkCommands.fixedAggregateAddCartItem()
createCommandMessageForNewAggregate() -> BenchmarkCommands.newAggregateAddCartItem()
createSmokeCommandMessage() -> BenchmarkCommands.smokeAddCartItem()
createBloomFilterIdempotencyChecker() -> BenchmarkIdempotency.bloomFilterChecker()
cartAggregateMetadata -> BenchmarkAggregates.cartMetadata
```

Add imports where needed:

```kotlin
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
```

- [ ] **Step 3: Replace event helper references**

Use these exact replacements:

```kotlin
createEventStream() -> BenchmarkEvents.singleEventStream()
createEventStream(aggregateId) -> BenchmarkEvents.singleEventStream(aggregateId)
createSingleEventStream() -> BenchmarkEvents.singleBodyEventStream()
```

Add:

```kotlin
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
```

- [ ] **Step 4: Delete legacy helper files**

Run:

```bash
git rm wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt
git rm wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/Events.kt
git rm wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt
```

- [ ] **Step 5: Verify no legacy helper references remain**

Run:

```bash
rg -n "HotPathFixture|createCommandMessageForNewAggregate\\(|createSmokeCommandMessage\\(|createBloomFilterIdempotencyChecker\\(|createSingleEventStream\\(|cartAggregateMetadata" wow-benchmarks/src/jmh/kotlin
rg -n "createCommandMessage\\(" wow-benchmarks/src/jmh/kotlin | rg -v "fun createCommandMessage"
rg -n "createEventStream\\(" wow-benchmarks/src/jmh/kotlin | rg -v "fun createEventStream"
```

Expected: command exits with status `1` and prints no matches.

- [ ] **Step 6: Compile after deleting legacy helpers**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit final migration cleanup**

Run:

```bash
git add -u wow-benchmarks/src/jmh/kotlin
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark
git commit -m "refactor(benchmarks): remove duplicated benchmark fixtures"
```

## Task 6: Validate Report And Baseline Workflow

**Files:**
- Modify: `wow-benchmarks/gradle/benchmark-reporting.gradle.kts`
- Modify: `wow-benchmarks/README.md`

- [ ] **Step 1: Create minimal local JMH result input**

Run:

```bash
mkdir -p wow-benchmarks/build/results/jmh wow-benchmarks/results
cat > wow-benchmarks/build/results/jmh/local.json <<'JSON'
[
  {
    "benchmark": "me.ahoo.wow.command.CommandFactoryBenchmark.createCommandMessage",
    "primaryMetric": {
      "score": 1000.0,
      "scoreError": 1.0,
      "scoreUnit": "ops/s"
    },
    "secondaryMetrics": {
      "gc.alloc.rate.norm": {
        "score": 128.0,
        "scoreError": 4.0
      }
    }
  }
]
JSON
cp wow-benchmarks/build/results/jmh/local.json wow-benchmarks/results/baseline.json
```

- [ ] **Step 2: Run report and baseline tasks against minimal input**

Run:

```bash
./gradlew :wow-benchmarks:generateBenchmarkReport :wow-benchmarks:generateGroupedBenchmarkReport :wow-benchmarks:benchmarkCompare :wow-benchmarks:updateBaseline
```

Expected: build ends with `BUILD SUCCESSFUL`. `generateGroupedBenchmarkReport` renders `Infrastructure I/O` as unavailable when `build/results/jmh/infrastructure.json` is absent.

- [ ] **Step 3: Verify report labels**

Run:

```bash
rg -n "Local Runtime|Infrastructure I/O|benchmarkLocal|benchmarkInfrastructure" wow-benchmarks/build/reports/jmh/grouped.md wow-benchmarks/README.md
```

Expected: output contains all four terms.

- [ ] **Step 4: Restore generated report artifacts**

Run:

```bash
git checkout -- wow-benchmarks/README.md
git checkout -- wow-benchmarks/results/baseline.json 2>/dev/null || rm -f wow-benchmarks/results/baseline.json
rm -f wow-benchmarks/build/results/jmh/local.json wow-benchmarks/build/reports/jmh/grouped.md
```

Expected: generated README and temporary baseline changes are removed from the worktree.

- [ ] **Step 5: Run packaging and smoke validation**

Run:

```bash
./gradlew :wow-benchmarks:jmhJar :wow-benchmarks:benchmarkSmoke
```

Expected: build ends with `BUILD SUCCESSFUL` and creates `wow-benchmarks/build/reports/jmh/benchmark-smoke.json`.

- [ ] **Step 6: Commit reporting fixes if this task changed tracked files**

Run:

```bash
git status --short
```

If only reporting or README source changes are present, commit them:

```bash
git add wow-benchmarks/gradle/benchmark-reporting.gradle.kts wow-benchmarks/README.md
git commit -m "refactor(benchmarks): align reports with local taxonomy"
```

If no tracked source changes are present, do not create an empty commit.

## Task 7: Final Verification And Cleanup

**Files:**
- Check: entire `wow-benchmarks` module

- [ ] **Step 1: Run required compile validation**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run required JMH jar validation**

Run:

```bash
./gradlew :wow-benchmarks:jmhJar
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run required smoke validation**

Run:

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
```

Expected: build ends with `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run local benchmark when time allows**

Run:

```bash
./gradlew :wow-benchmarks:benchmarkLocal
```

Expected: build ends with `BUILD SUCCESSFUL` and creates `wow-benchmarks/build/results/jmh/local.json`.

If this takes too long for the session, stop it with Ctrl-C and record the elapsed time and that `benchmarkSmoke` already passed.

- [ ] **Step 5: Run infrastructure benchmark only when services are available**

Check ports:

```bash
nc -z localhost 6379 && echo "redis-ok"
nc -z localhost 27017 && echo "mongo-ok"
```

If both commands print their `*-ok` line, run:

```bash
./gradlew :wow-benchmarks:benchmarkInfrastructure
```

Expected: build ends with `BUILD SUCCESSFUL` and creates `wow-benchmarks/build/results/jmh/infrastructure.json`.

If either service is unavailable, run:

```bash
./gradlew :wow-benchmarks:benchmarkInfrastructure --stacktrace
```

Expected: failure output contains the explicit `Infrastructure I/O benchmarks` service message from `InfrastructureAvailability`.

- [ ] **Step 6: Run final static checks for old taxonomy**

Run:

```bash
rg -n "benchmarkInternal|benchmarkExternal|Internal Results|External Results|Internal Lowest|External Lowest" wow-benchmarks
```

Expected: command exits with status `1` and prints no matches.

- [ ] **Step 7: Run final static checks for legacy fixture files**

Run:

```bash
test ! -e wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/command/Commands.kt
test ! -e wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/eventsourcing/Events.kt
test ! -e wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/hotpath/HotPathFixture.kt
```

Expected: all commands exit with status `0`.

- [ ] **Step 8: Review worktree state**

Run:

```bash
git status --short
git log --oneline -n 8
```

Expected: `git status --short` is empty after all intended commits. `git log` shows the benchmark refactor commits on top of the design and plan commits.
