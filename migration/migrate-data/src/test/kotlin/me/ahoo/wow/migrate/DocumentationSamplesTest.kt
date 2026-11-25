/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.ReservationTenancy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The configuration and the map the documentation shows (`documentation/docs/{en,zh}/migrate/data.md` and
 * `reference/migrate-data.md`) read as this tool reads them, every property one it defines: the pages show the files
 * in `src/test/resources/docs/` as they are; and those of the Kubernetes recipe (`deploy/migrate-data`).
 */
class DocumentationSamplesTest {
    private val samples = Path.of("src/test/resources/docs")

    @Test
    fun `the configuration the pages show reads`() {
        val config = MigrationConfig.read(samples.resolve("migrate-data.json"))
        config.map.assert().isEqualTo(samples.toAbsolutePath().resolve("migration-map.json").toString())
        config.target.type.assert().isEqualTo(TargetConfig.POSTGRES)
        MigrationConfig.resolve(config.target.password) { "secret" }.assert().isEqualTo("secret")
        config.partitionsOf("example").assert().isEqualTo(64)
        config.compensation!!.service.assert().isEqualTo("order-service")
    }

    @Test
    fun `the Kubernetes recipe's configuration reads, its checkpoint in the target and its classes where the Job puts them`() {
        val recipe = Path.of("../../deploy/migrate-data")
        val config = MigrationConfig.read(recipe.resolve("migrate-data.json"))
        config.checkpointName.assert().isEqualTo("order-service")
        config.manifests.assert().isEmpty()
        config.classpath.assert().containsExactly("/service/lib")
        MigrationConfig.resolve(config.source.uri) { "mongodb://9x" }.assert().isEqualTo("mongodb://9x")
        MigrationConfig.resolve(config.target.password) { if (it == "TARGET_PG_PASSWORD") "secret" else null }
            .assert().isEqualTo("secret")
        Files.readString(recipe.resolve("migration-map.json"))
            .assert().isEqualTo(Files.readString(samples.resolve("migration-map.json")))
    }

    @Test
    fun `the Job fails at once on the tool's verdicts alone, and retries an unavailable store and a run out of memory`() {
        // The image's -XX:+ExitOnOutOfMemoryError ends the process with the JVM's own exit code, measured here: the
        // README says the Job runs it again from its checkpoint, and the reference gives its code.
        val oom = Files.createTempFile("oom", ".java")
        Files.writeString(
            oom,
            "public class Oom { public static void main(String[] a) { var held = new java.util.ArrayList<long[]>();" +
                " while (true) held.add(new long[1_000_000]); } }",
        )
        val java = ProcessHandle.current().info().command().get()
        val process = ProcessBuilder(java, "-Xmx16m", "-XX:+ExitOnOutOfMemoryError", oom.toString())
            .redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        val oomExit = process.waitFor()
        val job = Files.readString(Path.of("../../deploy/migrate-data/job/job.yaml"))
        val failJob = Regex("""action: FailJob[\s\S]*?values: \[([^\]]*)]""").find(job)!!.groupValues[1]
            .split(',').map { it.trim().toInt() }

        oomExit.assert().describedAs("the JVM's exit on an OutOfMemoryError").isEqualTo(3)
        failJob.assert().describedAs("the exit codes that fail the Job at once")
            .containsExactly(MigrateData.FAILED, MigrateData.USAGE_ERROR, MigrateData.DIFFERS)
            .doesNotContain(oomExit, MigrateData.UNAVAILABLE)
    }

    @Test
    fun `the map the pages show reads`() {
        val map = MigrationMap.read(Files.readString(samples.resolve("migration-map.json")))
        val order = map.aggregates.single()
        order.to.assert().isEqualTo("example.order")
        order.events.filter { it.retire }.map { it.to }.assert().containsExactly("OrderArchived")
        order.events.filter { it.drop }.map { it.from }.assert().containsExactly("order_noted")
        order.reservations.single().tenancy.assert().isEqualTo(ReservationTenancy.SYSTEM)
        order.state.ignore.assert().containsExactly("payable")
        order.events.single { it.tags }.to.assert().isEqualTo("OrderTagged")
        order.tags.map { it.key to it.to }.assert()
            .containsExactly("region" to "region", "team" to "teams", "channel" to "channel", "legacy" to null)
        order.tagOf("channel")?.source.assert().isEqualTo(TagSource.DERIVED)
        map.subscriptions.map { it.from }
            .assert().containsExactly("order-service.OrderSaga", "order-service.OrderProjector")
        map.headers.assert().containsEntry("app_id", "app_id")
        val (audit, defect) = map.excluded
        audit.source().assert().isEqualTo(NineXAggregate("order-service", "audit"))
        defect.excludedStream().assert().isEqualTo(NineXAggregate("order-service", "order") to RefusedIds.EMPTY)
    }
}
