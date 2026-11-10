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
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import java.nio.file.Files

internal class MigrateDataCheckTest : MigrateDataFixtures() {
    @Test
    fun `draft-map drafts a map check accepts, and never replaces one without --force`() {
        val config = config().toString()
        val map = directory.resolve("migration-map.json")
        run("draft-map", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("$map exists: draft-map writes a new map, so pass --force to replace it, or --out.")
        Files.delete(map)

        run("draft-map", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("\"from\" : \"example-service.order\"").contains("Nothing was written.")
        Files.exists(map).assert().isFalse()

        run("draft-map", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("UNSOURCED example.order").contains("Draft written to $map: 2 aggregates")
        val draft = MigrationMap.read(Files.readString(map))
        draft.aggregates.map { it.to }.assert().containsExactly("example.cart", "example.order")
        draft.exceptions.assert().isNotEmpty()
        run("check", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("WARNING the draft's exception, for a person to resolve and delete: UNSOURCED")

        run("draft-map", "--config", config, "--force").assert().isEqualTo(MigrateData.OK)
        val elsewhere = directory.resolve("draft.json")
        run("draft-map", "--config", config, "--out", elsewhere.toString()).assert().isEqualTo(MigrateData.OK)
        MigrationMap.read(Files.readString(elsewhere)).assert().isEqualTo(draft)
        run("draft-map", "--config", config, "--out").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("draft-map", "--config", config, "--restart").assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `check reports and writes nothing`() {
        run("check", "--config", config().toString()).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Mapping check passed.").doesNotContain("(createTime, _id)")
        closed.assert().isTrue()
        Files.exists(directory.resolve("migrate-data.checkpoint.json")).assert().isFalse()
    }

    @Test
    fun `draft-map and check should fail on a store without an event stream, naming the store`() {
        // What a misnamed database reads as: a store holding nothing.
        nineX = InMemoryNineXEventSource()
        val path = config().toString()
        run("draft-map", "--config", path, "--dry-run").assert().isEqualTo(MigrateData.FAILED)
        run("check", "--config", path).assert().isEqualTo(MigrateData.FAILED)
        printed.lines().filter { it.startsWith("FAILED") }.assert().containsOnly(
            "FAILED the in-memory store holds no 9.x event stream: check source.database, and the variable an env: " +
                "value names.",
        ).hasSize(2)
    }

    @Test
    fun `check --decode reads every 9x event with the V10 models, and lists each that does not read`() {
        val config = config().toString()
        run("check", "--config", config, "--decode").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order decoded: 4 streams, 4 events, every one read")
            .contains("Decode check passed: the V10 models read every 9.x event.")
        NineXExample.cartRemoving("cart-null", "[null]").forEach(source::add)
        run("check", "--config", config, "--decode").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("PROBLEM decode: the 9.x event cart_item_removed@0.0.1 of 9.x stream ")
            .contains(
                "Decode check failed: 1 9.x events of 1 types do not read, and 0 streams do not convert. Give each " +
                    "type an @Upgrade that cleans its old shape.",
            )
        // Nothing is decoded on a map the check fails.
        val payment = NineXExample.stream("cart-stream-v1.json").let {
            it.copy(id = "payment-1", aggregate = NineXAggregate("example-service", "payment"))
        }
        source.add(payment)
        run("check", "--config", config, "--decode").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().endsWith("Decode check not run: the mapping check failed.${System.lineSeparator()}")
    }
}
