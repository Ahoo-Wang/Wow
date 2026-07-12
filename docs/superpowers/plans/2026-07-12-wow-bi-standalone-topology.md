# Wow BI Standalone ClickHouse Topology Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate complete, executable ClickHouse BI scripts for either clustered or true standalone deployments through the same `BiScriptGenerator` API.

**Architecture:** A public sealed `ClickHouseTopology` makes deployment state valid by construction. `StateExpansionPlanner` continues to emit topology-neutral logical names, while an internal topology DDL policy supplies scope, physical table names, engines, distributed facades, and drop targets to `ClickHouseScriptRenderer`.

**Tech Stack:** Kotlin 2.4.0, JVM 17, Gradle, JUnit Jupiter, FluentAssert, Spring Boot configuration properties, Reactor WebFlux, Testcontainers, ClickHouse 24.8, VitePress.

## Global Constraints

- Standalone SQL contains no `ON CLUSTER`, `Replicated*`, `Distributed`, `_local`, or ZooKeeper path.
- Cluster remains the default topology.
- Remove the old top-level `cluster`, `installation`, `shard`, and `replica` options without compatibility adapters.
- Keep one `BiScriptGenerator -> StateExpansionPlanner -> ClickHouseScriptRenderer` generation path.
- Planner output and lossless JSON recovery semantics remain topology-independent.
- Run every behavior change through RED, GREEN, and refactor before proceeding.
- Preserve the unrelated local modification in `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`.

---

## File Map

- Create `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ClickHouseTopology.kt`: public valid-by-construction deployment topology.
- Create `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseTopologyDdl.kt`: internal physical DDL policy selected from the public topology.
- Modify `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt`: replace flat cluster fields with `topology`.
- Modify `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRenderer.kt`: render storage and drops through the topology policy.
- Modify `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/BiTableNaming.kt`: expose topology-neutral logical table naming.
- Modify `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`: use topology-neutral naming only.
- Modify `wow-bi/src/test/kotlin/me/ahoo/wow/bi/*` and renderer/planner tests: domain, API, SQL graph, ordering, and quoting contracts.
- Create `wow-bi/src/test/resources/expected_bi_cluster_script.sql` and `expected_bi_standalone_script.sql`: full generator contracts.
- Modify `wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt`: execute both topologies on appropriately configured containers.
- Modify `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/bi/BiScriptProperties.kt`: nested topology configuration and sole adapter.
- Modify `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`: configuration and route integration contracts.
- Modify `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`: compile and preserve direct standalone option delivery.
- Modify English and Chinese BI/configuration docs: document only the current nested topology model.

---

### Task 1: Add the Valid-by-Construction Public Topology

**Files:**
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ClickHouseTopology.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiPublicApiTest.kt`

**Interfaces:**
- Produces: `sealed interface ClickHouseTopology`
- Produces: `ClickHouseTopology.Standalone`
- Produces: `ClickHouseTopology.Cluster(name, installation, shard, replica)`
- Consumes: no renderer or Spring types.

- [ ] **Step 1: Write failing topology validation and public API tests**

Add focused tests before the production type exists:

```kotlin
@Test
fun `should model standalone without cluster state`() {
    ClickHouseTopology.Standalone.assert().isEqualTo(ClickHouseTopology.Standalone)
    ClickHouseTopology.Standalone::class.declaredMemberProperties.assert().isEmpty()
}

@Test
fun `should use designed cluster defaults`() {
    ClickHouseTopology.Cluster().assert().isEqualTo(
        ClickHouseTopology.Cluster(
            name = "{cluster}",
            installation = "{installation}",
            shard = "{shard}",
            replica = "{replica}",
        )
    )
}

@Test
fun `should reject invalid cluster values`() {
    listOf<() -> ClickHouseTopology.Cluster>(
        { ClickHouseTopology.Cluster(name = " ") },
        { ClickHouseTopology.Cluster(installation = "bad\nvalue") },
        { ClickHouseTopology.Cluster(shard = "\t") },
        { ClickHouseTopology.Cluster(replica = "bad\u0000value") },
    ).all { runCatching(it).isFailure }.assert().isTrue()
}
```

Add `ClickHouseTopology::class.qualifiedName` to the exact public type list in `BiPublicApiTest`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptOptionsTest" \
  --tests "me.ahoo.wow.bi.BiPublicApiTest"
```

Expected: Kotlin test compilation fails because `ClickHouseTopology` does not exist.

- [ ] **Step 3: Implement the sealed topology and validation**

Create the production type with no renderer behavior:

```kotlin
sealed interface ClickHouseTopology {
    data object Standalone : ClickHouseTopology

    data class Cluster(
        val name: String = "{cluster}",
        val installation: String = "{installation}",
        val shard: String = "{shard}",
        val replica: String = "{replica}",
    ) : ClickHouseTopology {
        init {
            name.requireTopologyValue("name")
            installation.requireTopologyValue("installation")
            shard.requireTopologyValue("shard")
            replica.requireTopologyValue("replica")
        }
    }
}

private fun String.requireTopologyValue(name: String) {
    require(isNotBlank()) { "$name must not be blank" }
    require(none { it == '\u0000' || it.isISOControl() }) {
        "$name must not contain control characters"
    }
}
```

- [ ] **Step 4: Run focused and module tests and verify GREEN**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptOptionsTest" \
  --tests "me.ahoo.wow.bi.BiPublicApiTest"
./gradlew :wow-bi:test
```

Expected: both commands pass; existing generation still uses the old flat fields until Task 2.

- [ ] **Step 5: Commit the topology model**

```bash
git add wow-bi/src/main/kotlin/me/ahoo/wow/bi/ClickHouseTopology.kt \
  wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt \
  wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiPublicApiTest.kt
git commit -m "feat(bi): model ClickHouse deployment topology"
```

---

### Task 2: Make Physical DDL Topology-Aware

**Files:**
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseTopologyDdl.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRenderer.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/BiTableNaming.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRendererTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/BiTableNamingTest.kt`
- Modify: `wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt`

**Interfaces:**
- `BiScriptOptions.topology: ClickHouseTopology`
- Internal `ClickHouseTopologyDdl.scopeClause: String`
- Internal `physicalTableName(logicalTableName: String): String`
- Internal `engineSql(kind: MergeTreeKind): String`
- Internal `distributedFacade(database, logicalTableName, physicalTableName, shardingKey): String?`
- Internal `dropTableNames(logicalTableName: String): List<String>`

- [ ] **Step 1: Write failing standalone renderer contracts**

Add a test that constructs `BiScriptOptions(topology = ClickHouseTopology.Standalone)` and verifies the complete family graph:

```kotlin
@Test
fun `should render a true standalone statement graph`() {
    val renderer = ClickHouseScriptRenderer(
        BiScriptOptions(topology = ClickHouseTopology.Standalone)
    )

    val command = renderer.renderCommandStatements(aggregate)
    val stateEvent = renderer.renderStateEventStatements(aggregate)
    val stateLast = renderer.renderStateLastStatements(aggregate)
    val sql = (renderer.renderGlobalStatements() + command + stateEvent + stateLast)
        .joinToString("\n")

    command.assert().hasSize(3)
    stateEvent.assert().hasSize(4)
    stateLast.assert().hasSize(2)
    sql.assert().doesNotContain("ON CLUSTER", "Replicated", "Distributed", "_local", "/clickhouse/")
    sql.assert().contains("ENGINE = MergeTree", "ENGINE = ReplacingMergeTree")
}
```

Add assertions that standalone clear has 9 statements before expansion views and that consumer/state-last/event/expansion views reference the logical business tables.

Update the option test to expect `BiScriptOptions().topology == ClickHouseTopology.Cluster()` and use reflection to assert the old top-level fields are absent.

Add a minimal integration test that generates standalone statements, starts a
vanilla `ClickHouseContainer` without `clickhouse-test-cluster.xml`, and executes
every statement:

```kotlin
@Test
fun `should execute complete standalone DDL without cluster configuration`() {
    val namedAggregate = aggregateMetadata<ClickHouseExpansionAggregate, ClickHouseExpansionState>()
    val result = BiScriptGenerator(
        BiScriptOptions(
            database = DATABASE,
            consumerDatabase = CONSUMER_DATABASE,
            topology = ClickHouseTopology.Standalone,
            timezone = "UTC",
        )
    ).generate(setOf(namedAggregate))

    ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).use { container ->
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            result.statements.forEach(connection::executeSql)
        }
    }
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptOptionsTest" \
  --tests "me.ahoo.wow.bi.renderer.ClickHouseScriptRendererTest"
./gradlew :wow-bi:integrationTest \
  --tests "me.ahoo.wow.bi.ClickHouseExpansionIntegrationTest.should execute complete standalone DDL without cluster configuration" \
  --rerun-tasks
```

Expected: unit test compilation fails because `BiScriptOptions` has no
`topology`; after only the tests compile, the vanilla ClickHouse execution fails
on clustered DDL. Both failures demonstrate missing standalone behavior.

- [ ] **Step 3: Introduce the internal DDL policy**

Use one topology policy selected exhaustively from the public sealed type:

```kotlin
internal sealed interface MergeTreeKind {
    data object AppendOnly : MergeTreeKind
    data class Replacing(val versionColumn: String) : MergeTreeKind
}

internal interface ClickHouseTopologyDdl {
    val scopeClause: String
    fun physicalTableName(logicalTableName: String): String
    fun engineSql(kind: MergeTreeKind): String
    fun distributedFacade(
        database: String,
        logicalTableName: String,
        physicalTableName: String,
        shardingKey: String,
    ): String?
    fun dropTableNames(logicalTableName: String): List<String>
}

internal fun ClickHouseTopology.toDdl(): ClickHouseTopologyDdl = when (this) {
    is ClickHouseTopology.Cluster -> ClusterTopologyDdl(this)
    ClickHouseTopology.Standalone -> StandaloneTopologyDdl
}
```

Cluster policy returns a leading `ON CLUSTER ...` scope, `<logical>_local`, replicated engines, one distributed facade, and `[logical, logical_local]` drop targets. Standalone returns an empty scope, the logical name itself, non-replicated engines, no facade, and `[logical]`.

- [ ] **Step 4: Replace flat options and migrate the renderer**

Change the options constructor:

```kotlin
data class BiScriptOptions(
    val database: String = "bi_db",
    val consumerDatabase: String = "bi_db_consumer",
    val topology: ClickHouseTopology = ClickHouseTopology.Cluster(),
    val timezone: String = "Asia/Shanghai",
    val kafkaBootstrapServers: String = DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
    val topicPrefix: String = DEFAULT_TOPIC_PREFIX,
    val maxExpansionDepth: Int = 5,
    val unsupportedTypeStrategy: UnsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
)
```

In `ClickHouseScriptRenderer`, create `private val topology = options.topology.toDdl()`. Replace direct `onCluster()`, replicated-path, `_local`, `Distributed`, and drop-list decisions with topology policy calls. Omit the distributed facade when the policy returns `null`.

Rename the logical naming method and all callers:

```kotlin
fun toTableName(namedAggregate: NamedAggregate, suffix: String): String
```

Do not pass topology into `StateExpansionPlanner` branches; it continues to use only `toTableName`.

- [ ] **Step 5: Update clustered call sites without changing clustered SQL**

Replace old constructors in `wow-bi` tests and integration sources with:

```kotlin
topology = ClickHouseTopology.Cluster(
    name = "cluster'name",
    installation = "install/name",
    shard = "shard'name",
    replica = "replica'name",
)
```

Keep the existing clustered quoting, ordering, recovery, and immutable-list assertions unchanged.

- [ ] **Step 6: Run renderer and module tests and verify GREEN**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptOptionsTest" \
  --tests "me.ahoo.wow.bi.expansion.BiTableNamingTest" \
  --tests "me.ahoo.wow.bi.renderer.ClickHouseScriptRendererTest" \
  --tests "me.ahoo.wow.bi.BiScriptGeneratorTest"
./gradlew :wow-bi:test
./gradlew :wow-bi:integrationTest \
  --tests "me.ahoo.wow.bi.ClickHouseExpansionIntegrationTest.should execute complete standalone DDL without cluster configuration" \
  --rerun-tasks
```

Expected: all commands pass; clustered assertions are unchanged, standalone has
the expected statement graph, and the complete standalone DDL executes on
vanilla ClickHouse.

- [ ] **Step 7: Commit the topology-aware renderer**

```bash
git add wow-bi/src/main wow-bi/src/test wow-bi/src/integrationTest
git commit -m "feat(bi): render standalone ClickHouse DDL"
```

---

### Task 3: Lock Both Complete Scripts with Snapshots

**Files:**
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt`
- Create: `wow-bi/src/test/resources/expected_bi_cluster_script.sql`
- Create: `wow-bi/src/test/resources/expected_bi_standalone_script.sql`

**Interfaces:**
- Consumes: `BiScriptGenerator(BiScriptOptions).generate(Set<NamedAggregate>).script`
- Produces: exact normalized Cluster and Standalone script contracts.

- [ ] **Step 1: Write the missing-snapshot failure**

Add one test with an explicit opt-in snapshot refresh path:

```kotlin
@Test
fun `should match complete scripts for every topology`() {
    assertSnapshot(
        "expected_bi_cluster_script.sql",
        BiScriptGenerator().generate(setOf(aggregate)).script,
    )
    assertSnapshot(
        "expected_bi_standalone_script.sql",
        BiScriptGenerator(
            BiScriptOptions(topology = ClickHouseTopology.Standalone)
        ).generate(setOf(aggregate)).script,
    )
}

private fun assertSnapshot(name: String, actual: String) {
    val path = Path.of("src/test/resources", name)
    if (System.getenv("UPDATE_BI_SCRIPT_SNAPSHOTS") == "true") {
        Files.writeString(path, actual)
    }
    actual.assert().isEqualTo(Files.readString(path))
}
```

- [ ] **Step 2: Run the snapshot test and verify RED**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptGeneratorTest.should match complete scripts for every topology"
```

Expected: failure because both snapshot files are absent.

- [ ] **Step 3: Generate and inspect both snapshots**

Run:

```bash
UPDATE_BI_SCRIPT_SNAPSHOTS=true ./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptGeneratorTest.should match complete scripts for every topology"
rg -n "ON CLUSTER|Replicated|Distributed|_local|/clickhouse/" \
  wow-bi/src/test/resources/expected_bi_cluster_script.sql
if rg -n "ON CLUSTER|Replicated|Distributed|_local|/clickhouse/" \
  wow-bi/src/test/resources/expected_bi_standalone_script.sql; then exit 1; fi
```

Expected: the first scan finds all required clustered constructs; the second scan exits with no matches.

- [ ] **Step 4: Run without refresh and verify GREEN**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptGeneratorTest.should match complete scripts for every topology"
./gradlew :wow-bi:test
```

Expected: both commands pass without changing either resource.

- [ ] **Step 5: Commit exact script contracts**

```bash
git add wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt \
  wow-bi/src/test/resources/expected_bi_cluster_script.sql \
  wow-bi/src/test/resources/expected_bi_standalone_script.sql
git commit -m "test(bi): lock cluster and standalone scripts"
```

---

### Task 4: Bind the Nested Spring Topology

**Files:**
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/bi/BiScriptProperties.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`

**Interfaces:**
- Produces: `BiScriptTopologyProperties(mode, cluster)`.
- Produces: `BiScriptTopologyMode.CLUSTER | STANDALONE`.
- Produces: `BiScriptClusterProperties(name, installation, shard, replica)`.
- Consumes: `ClickHouseTopology` through the sole `toBiScriptOptions` adapter.

- [ ] **Step 1: Write failing nested binding tests**

Add tests for standalone output, explicit clustered output, and invalid mixed configuration:

```kotlin
@Test
fun `should bind standalone BI topology`() {
    webFluxContextRunner()
        .withPropertyValues("${BiScriptProperties.PREFIX}.topology.mode=STANDALONE")
        .run { context ->
            context.assert().hasNotFailed()
            context.generateBiScript().assert()
                .doesNotContain("ON CLUSTER", "Replicated", "Distributed", "_local")
        }
}

@Test
fun `should reject cluster fields in standalone mode`() {
    webFluxContextRunner()
        .withPropertyValues(
            "${BiScriptProperties.PREFIX}.topology.mode=STANDALONE",
            "${BiScriptProperties.PREFIX}.topology.cluster.name=unused",
        )
        .run { context ->
            context.startupFailure.assert().isNotNull()
            context.startupFailure!!.causeChainMessages().assert()
                .contains("topology.cluster must not be configured in STANDALONE mode")
        }
}
```

Update the existing explicit cluster test to use `topology.mode=CLUSTER` and `topology.cluster.*`. Add a test proving removed flat keys leave the default `ClickHouseTopology.Cluster()` unchanged.

- [ ] **Step 2: Run the starter test and verify RED**

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
```

Expected: standalone binding assertions fail because nested topology properties do not exist.

- [ ] **Step 3: Implement nested properties and the sole adapter**

Use nullable cluster properties so the adapter can distinguish omission from a configured value:

```kotlin
data class BiScriptTopologyProperties(
    val mode: BiScriptTopologyMode = BiScriptTopologyMode.CLUSTER,
    val cluster: BiScriptClusterProperties? = null,
)

enum class BiScriptTopologyMode { CLUSTER, STANDALONE }

data class BiScriptClusterProperties(
    val name: String? = null,
    val installation: String? = null,
    val shard: String? = null,
    val replica: String? = null,
)
```

Replace the flat properties in `BiScriptProperties` with:

```kotlin
val topology: BiScriptTopologyProperties = BiScriptTopologyProperties()
```

Map exhaustively:

```kotlin
private fun BiScriptTopologyProperties.toTopology(): ClickHouseTopology = when (mode) {
    BiScriptTopologyMode.CLUSTER -> ClickHouseTopology.Cluster(
        name = cluster?.name ?: defaultCluster.name,
        installation = cluster?.installation ?: defaultCluster.installation,
        shard = cluster?.shard ?: defaultCluster.shard,
        replica = cluster?.replica ?: defaultCluster.replica,
    )
    BiScriptTopologyMode.STANDALONE -> {
        require(cluster == null) {
            "topology.cluster must not be configured in STANDALONE mode"
        }
        ClickHouseTopology.Standalone
    }
}

private val defaultCluster = ClickHouseTopology.Cluster()
```

Pass only the validated `ClickHouseTopology` into `BiScriptOptions`. Update direct WebFlux tests to construct nested cluster options or standalone options; do not add topology knowledge to the handler.

- [ ] **Step 4: Run starter, WebFlux, and OpenAPI checks and verify GREEN**

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
./gradlew :wow-webflux:check :wow-spring-boot-starter:check :wow-openapi:check
```

Expected: nested cluster and standalone binding pass; invalid mixed configuration fails only in its asserted test case.

- [ ] **Step 5: Commit the configuration boundary**

```bash
git add wow-spring-boot-starter/src/main \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt
git commit -m "feat(starter): configure ClickHouse topology"
```

---

### Task 5: Execute Standalone SQL on Vanilla ClickHouse

**Files:**
- Modify: `wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt`

**Interfaces:**
- Consumes: complete `BiScriptResult.statements` for both topologies.
- Produces: executable proof against clustered and vanilla ClickHouse 24.8 containers.

- [ ] **Step 1: Expand the standalone integration contract**

Extract the current assertion body into `verifyGeneratedDdl(case)` and define explicit cases:

```kotlin
private data class TopologyCase(
    val displayName: String,
    val topology: ClickHouseTopology,
    val configureContainer: ClickHouseContainer.() -> Unit,
    val forbiddenObjectSuffixes: Set<String>,
)

private fun topologyCases() = listOf(
    TopologyCase(
        displayName = "cluster",
        topology = ClickHouseTopology.Cluster(
            name = CLUSTER,
            installation = "test",
            shard = "01",
            replica = "01",
        ),
        configureContainer = {
            withCopyFileToContainer(
                MountableFile.forClasspathResource(CLUSTER_CONFIG_RESOURCE),
                CLUSTER_CONFIG_PATH,
            )
        },
        forbiddenObjectSuffixes = emptySet(),
    ),
    TopologyCase(
        displayName = "standalone",
        topology = ClickHouseTopology.Standalone,
        configureContainer = {},
        forbiddenObjectSuffixes = setOf("_local"),
    ),
)
```

The standalone case must execute every generated statement on a container that receives no cluster config. Query `system.tables` and `engine` to assert no `_local` name and no `Distributed` engine.

- [ ] **Step 2: Run the expanded integration contract**

Run:

```bash
./gradlew :wow-bi:integrationTest \
  --tests "me.ahoo.wow.bi.ClickHouseExpansionIntegrationTest" --rerun-tasks
```

Expected: both topology cases execute. This task adds test-only depth after Task
2 already proved standalone behavior RED then GREEN; it does not add production
behavior.

- [ ] **Step 3: Complete topology-specific object and data-path assertions**

Keep common row fixtures and projection assertions shared. For standalone, additionally:

```sql
SELECT database, name, engine
FROM system.tables
WHERE database IN ('bi_it', 'bi_it_consumer')
  AND (endsWith(name, '_local') OR engine = 'Distributed')
```

Assert the query returns no rows. Insert a command row into the logical command table and a state row into the logical state table; verify the event view, state-last table, root expansion view, and child expansion views can be queried through logical names.

Use explicit logical-table inserts so the proof does not depend on a live Kafka
broker:

```kotlin
connection.executeSql(
    """
    INSERT INTO ${qualified(DATABASE, COMMAND_TABLE)}
    (id, context_name, aggregate_name, name, header, aggregate_id, tenant_id,
     owner_id, space_id, request_id, aggregate_version, is_create, allow_create,
     body_type, body, create_time)
    VALUES ('command-1', 'bi-integration-service', 'nullable', 'Create', map(),
            'aggregate-1', '', '', '', 'request-1', 1, true, true,
            'example.Create', '{}', toDateTime(0, 'UTC'))
    """.trimIndent()
)

connection.prepareStatement(
    """
    INSERT INTO ${qualified(DATABASE, STATE_TABLE)}
    (id, context_name, aggregate_name, header, aggregate_id, tenant_id, owner_id,
     space_id, command_id, request_id, version, state, body, first_operator,
     first_event_time, create_time, tags, deleted)
    VALUES ('state-1', 'bi-integration-service', 'nullable', map(), 'aggregate-1',
            '', '', '', 'command-1', 'request-1', 1, ?,
            ['{"id":"event-1","name":"Created","revision":"1","bodyType":"example.Created","body":"{}"}'],
            '', toDateTime(0, 'UTC'), toDateTime(0, 'UTC'), map(), false)
    """.trimIndent()
).use { statement ->
    statement.setString(1, STATE_ROWS.single { it.first == "row-normal" }.second)
    statement.executeUpdate()
}
```

Assert one command row, one event-view row, one state-last row, and the existing
root/child recovery projections for `aggregate-1`.

- [ ] **Step 4: Run integration twice and verify GREEN/stability**

Run:

```bash
./gradlew :wow-bi:integrationTest --rerun-tasks
./gradlew :wow-bi:integrationTest --rerun-tasks
```

Expected: both executions pass for Cluster and Standalone using ClickHouse `24.8.14.39-alpine`.

- [ ] **Step 5: Commit executable standalone proof**

```bash
git add wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt
git commit -m "test(bi): execute standalone ClickHouse topology"
```

---

### Task 6: Publish the Current-Only Contract and Run Final Gates

**Files:**
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `documentation/docs/en/guide/configuration.md`
- Modify: `documentation/docs/zh/guide/configuration.md`

**Interfaces:**
- Documents: `ClickHouseTopology`, nested Spring properties, both physical DDL graphs, and the single generator API.

- [ ] **Step 1: Update English and Chinese documentation**

Document these exact current configurations:

```yaml
wow:
  bi:
    script:
      topology:
        mode: STANDALONE
```

```yaml
wow:
  bi:
    script:
      topology:
        mode: CLUSTER
        cluster:
          name: production
          installation: clickhouse
          shard: '{shard}'
          replica: '{replica}'
```

Update the public contract count to include `ClickHouseTopology`. Show standalone `MergeTree`/`ReplacingMergeTree` DDL without clustered clauses and retain clustered examples. Remove all claims that the flat cluster keys are current configuration.

- [ ] **Step 2: Build docs and scan the contract**

Run:

```bash
cd documentation && pnpm docs:build
cd ..
rg -n "wow\.bi\.script\.(cluster|installation|shard|replica)" \
  documentation wow-bi/src/main wow-webflux/src/main wow-spring-boot-starter/src/main || true
rg -n "cluster: String|installation: String|shard: String|replica: String" \
  wow-bi/src/main || true
```

Expected: VitePress build passes; scans return no old flat public option or configuration declarations.

- [ ] **Step 3: Run the complete JVM verification matrix**

Run:

```bash
./gradlew :wow-bi:clean :wow-bi:test :wow-bi:integrationTest --rerun-tasks
./gradlew --no-daemon :wow-bi:check :wow-webflux:check \
  :wow-spring-boot-starter:check :wow-openapi:check detekt --rerun-tasks
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run static SQL and worktree gates**

Run:

```bash
git diff --check
rg -n "ON CLUSTER|Replicated|Distributed|_local|/clickhouse/" \
  wow-bi/src/test/resources/expected_bi_cluster_script.sql
if rg -n "ON CLUSTER|Replicated|Distributed|_local|/clickhouse/" \
  wow-bi/src/test/resources/expected_bi_standalone_script.sql; then exit 1; fi
git status --short
```

Expected: clustered constructs exist only in the cluster snapshot; standalone scan finds none; the only unrelated worktree modification remains the pre-existing `2026-07-10` design document.

- [ ] **Step 5: Commit documentation and final contract updates**

```bash
git add documentation/docs/en/guide/bi.md \
  documentation/docs/zh/guide/bi.md \
  documentation/docs/en/guide/configuration.md \
  documentation/docs/zh/guide/configuration.md
git commit -m "docs(bi): document ClickHouse deployment topologies"
```

- [ ] **Step 6: Review the complete branch without mutating GitHub**

Run:

```bash
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
git status -sb
```

Expected: commits are scoped to the standalone topology feature and its design/plan; no unrelated local modification is staged or committed.
