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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import me.ahoo.wow.manifest.Creation as ManifestCreation

/**
 * The route of a command whose body names its aggregate (`@AggregateId`): without an id, to the aggregate the body
 * names; the lifecycle commands keep their routes by id.
 */
class AggregateIdRoutesTest {
    data class Stock(val sku: String?, val quantity: Int)
    data class Restock(val sku: String, val quantity: Int)
    data class Stocked(val quantity: Int)

    class Shelf {
        var quantity = 0
    }

    private val shelfModel = aggregate<Shelf>(CONTEXT, "shelf") {
        initial { Shelf() }
        decide<Stock>(Creation.REQUIRED) { _, body -> Decision.events(Stocked(body.quantity)) }
        decide<Restock> { _, body -> Decision.events(Stocked(body.quantity)) }
        evolve<Stocked> { quantity += it.quantity }
        aggregateId<Stock> { it.sku }
        aggregateId<Restock> { it.sku }
    }

    /** The cart's Manifest with the shelf as the compiler writes its commands: by no id alone, and the id's property. */
    private val manifest: Manifest = cartManifest().let { manifest ->
        fun command(name: String, creation: ManifestCreation) = CommandManifest(
            name = name,
            schema = Manifest.schemaRef(name),
            creation = creation,
            routes = listOf(CommandRoutes.unaddressed(CONTEXT, "shelf", name)),
            idProperty = "sku",
        )
        val shelf = AggregateManifest(
            name = "shelf",
            state = StateManifest(Manifest.schemaRef("Shelf")),
            commands = listOf(command("Stock", ManifestCreation.REQUIRED), command("Restock", ManifestCreation.NONE)) +
                Lifecycle.COMMANDS.keys.sorted().map { SystemCommands.lifecycle(CONTEXT, "shelf", it) },
        )
        val context = manifest.contexts.single()
        manifest.copyWith(contexts = listOf(context.copyWith(aggregates = context.aggregates + shelf)))
    }

    private val node = HttpNode(manifest = manifest, aggregates = listOf(shelfModel))

    private fun Answer.assertSucceeded(): Answer {
        status.assert().describedAs(body.toString()).isEqualTo(200)
        body!!["status"].asString().assert().isEqualTo("succeeded")
        return this
    }

    @Test
    fun `a command goes by no id to the aggregate its body names, and its lifecycle by its id`() {
        node.post("/shop/shelf/stock", """{"sku":"mug","quantity":2}""").assertSucceeded()
            .body!!["ref"]["id"].asString().assert().isEqualTo("mug")
        node.post("/shop/shelf/restock", """{"sku":"mug","quantity":3}""").assertSucceeded()
            .body!!["version"].asInt().assert().isEqualTo(2)
        node.get("/shop/shelf/mug/state").body!!["state"]["quantity"].asInt().assert().isEqualTo(5)
        // A creation whose body names none is given an id.
        node.post("/shop/shelf/stock", """{"quantity":1}""").assertSucceeded()
            .body!!["ref"]["id"].asString().assert().isNotEqualTo("mug")
        // No route takes an id for it.
        node.post("/shop/shelf/mug/restock", """{"sku":"mug","quantity":1}""").status.assert().isEqualTo(404)
        val blank = node.post("/shop/shelf/restock", """{"sku":" ","quantity":1}""")
        blank.status.assert().describedAs(blank.body.toString()).isEqualTo(400)
        blank.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        node.client.delete().uri("/shop/shelf/mug").header("X-User", "alice").answer().assertSucceeded()
    }
}
