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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * A cart whose 9.x streams and snapshot record no owner, imported by a map that fills a missing owner with the id
 * ([AggregateMapping.missingOwner]): the import counts what it filled, `compare` holds the 9.x owner to the same rule
 * and finds no difference, and the reverse export writes the filled owner back.
 */
class MissingOwnerImportTest {
    private val cart = NineXAggregate("example-service", "cart")
    private val events = InMemoryNineXEventSource(NineXExample.streams()).also { source ->
        NineXExample.cartAs("cart-n").forEach(source::add)
    }
    private val log = InMemoryEventLog(4)
    private val checkpoints = InMemoryCheckpointRepository()
    private val printed = mutableListOf<String>()
    private val plan = MigrationPlan(MigrationMap.read(NineXExample.fillingMapText()), NineXExample.ownedCartManifest)

    /** The cart's 9.x snapshot as that of cart-n, which records no owner. */
    private val snapshot = (WowJson.mapper.readTree(NineXExample.resource("cart-snapshot.json")) as ObjectNode)
        .put("aggregateId", "cart-n").put("ownerId", "")
        .also { (it.get("state") as ObjectNode).put("id", "cart-n") }
        .let(NineXSnapshot::parse)

    @Test
    fun `the import counts the owners it fills, and compare finds the filled owner the 9x snapshot implies`() = runTest {
        val progress = EventStreamImporter(events, plan, { log }, checkpoints).importAll(cart)
        progress.filled.assert().isEqualTo(4)
        progress.render().assert().contains("4 owners filled with their id, ")
        val ref = plan.of(cart).target.ref("(0)", "cart-n")
        log.read(ref).toList().map { it.scope.ownerId to it.headers[FILLED_OWNER_HEADER] }.assert()
            .containsOnly("cart-n" to "aggregate-id")
        val report = StateComparison(
            InMemoryNineXSnapshotSource(listOf(snapshot), events),
            events,
            plan,
            MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel)),
            { log },
            checkpoints,
            CompareOptions(),
            { printed += it },
        ).compare(cart)
        report.compared.assert().isEqualTo(1)
        report.passed.assert().describedAs(printed.toString()).isTrue()
        // The reverse export writes the owner V10 holds back: 9.x then holds the id too.
        StreamReverter(plan) { _, _ -> emptyList() }.revert(log.read(ref).toList().first()).ownerId
            .assert().isEqualTo("cart-n")
    }

    @Test
    fun `progress without a filled owner says nothing of it`() {
        ImportProgress(cart, 1, 4, 0, 4, 0, 1, done = false).render().assert().doesNotContain("owners filled")
    }
}
