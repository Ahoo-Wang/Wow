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

import com.mongodb.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * PA-4: `draft-map` on the 9.x example's MongoDB store and the V10 Manifest of its port. A dry run prints the draft
 * and writes nothing; the draft maps both aggregates and every event and command the store holds, lists the V10 names
 * no 9.x name matches as exceptions, and passes the check and a dry-run import as the hand-written map does.
 */
class NineXDraftMapTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example()
    private val map: Path get() = directory.resolve("migration-map.json")

    private fun config(): Path {
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}" },
                  "target": { "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$TARGET" },
                  "partitions": { "${NineXExample.CONTEXT}": 4 }
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    private val printed: String get() = output.toString()

    @Test
    fun `the 9x example's draft should map every name its store holds, and pass the check and a dry-run import`() {
        val config = config().toString()
        run("draft-map", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("Nothing was written.")
        Files.exists(map).assert().isFalse()

        run("draft-map", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        val draft = MigrationMap.read(Files.readString(map))
        val handWritten = NineXExample.map
        draft.aggregates.map { it.from to it.to }.assert()
            .containsExactlyInAnyOrderElementsOf(handWritten.aggregates.map { it.from to it.to })
        draft.aggregates.forEach { aggregate ->
            val written = handWritten.aggregates.single { it.from == aggregate.from }
            // The hand-written map also maps names no stream records; the draft maps what the store holds, alike.
            written.commands.map { it.from to it.to }.assert()
                .containsAll(aggregate.commands.map { it.from to it.to })
            written.events.map { it.from to it.to }.assert().containsAll(aggregate.events.map { it.from to it.to })
        }
        draft.exceptions.assert().contains(
            "UNSOURCED example.order event OrderReceived: no 9.x event of example-service.order matches it",
            "UNSOURCED example.order command ReceiptOrder: no 9.x command of example-service.order matches it",
        )
        draft.exceptions.all { it.startsWith("UNSOURCED ") }.assert().describedAs(draft.exceptions.toString()).isTrue()

        run("check", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("Mapping check passed.")
        run("import", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order done: 1 instances, 4 streams")
    }

    private companion object {
        const val TARGET = "wow_v10_drafted"
    }
}
