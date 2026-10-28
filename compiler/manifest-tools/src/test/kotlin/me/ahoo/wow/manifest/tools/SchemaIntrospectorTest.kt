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

package me.ahoo.wow.manifest.tools

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

class SchemaIntrospectorTest {
    data class Money(val cents: Long) {
        @JsonValue
        fun text(): String = "$cents"
    }

    enum class Color { RED, GREEN }

    data class AddItem(
        @JsonProperty("sku") val productId: String,
        val quantity: Int = 1,
        val note: String?,
        val color: Color,
        val price: Money,
        val tags: List<Tag> = emptyList(),
        val mainTag: Tag? = null,
    )

    data class Tag(val name: String)

    class CartState(val id: String) {
        var owner: String? = null
        val items = mutableMapOf<String, Int>()
    }

    private fun fragment(): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = "shop",
                aggregates = listOf(
                    AggregateManifest(
                        name = "cart",
                        state = StateManifest(schemaRef(CartState::class.java.name)),
                        commands = listOf(CommandManifest("AddItem", schema = schemaRef(AddItem::class.java.name))),
                    ),
                ),
            ),
        ),
    )

    /** Money is written as its text (`@JsonValue`), which introspection cannot see: its schema is declared. */
    private val moneySchema = """{"type":"string","pattern":"^[0-9]+$"}"""

    private val introspector = SchemaIntrospector(javaClass.classLoader, mapOf(Money::class.java.name to moneySchema))

    @Suppress("UNCHECKED_CAST")
    private fun defOf(
        manifest: Manifest,
        type: Class<*>
    ): Map<String, Any?> = manifest.defs[type.name] as Map<String, Any?>

    @Test
    fun `schemas should follow what Jackson writes`() {
        val completed = introspector.complete(fragment())
        completed.unresolvedSchemaRefs.assert().isEmpty()
        val addItem = defOf(completed, AddItem::class.java)
        addItem["type"].assert().isEqualTo("object")
        val properties = addItem["properties"] as Map<*, *>
        properties.keys.assert()
            .containsExactlyInAnyOrder("sku", "quantity", "note", "color", "price", "tags", "mainTag")
        // Required: non-null constructor parameters without a default.
        (addItem["required"] as List<*>).assert().containsExactlyInAnyOrder("sku", "color", "price")
        properties["note"].assert().isEqualTo(mapOf("type" to listOf("string", "null")))
        properties["color"].assert().isEqualTo(
            mapOf("type" to "string", "enum" to listOf("RED", "GREEN"), "x-wow-class" to Color::class.java.name),
        )
        // A type referred to more than once is a definition.
        properties["tags"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("\$ref" to schemaRef(Tag::class.java.name))),
        )
        defOf(completed, Tag::class.java).assert().isEqualTo(
            mapOf(
                "type" to "object",
                "properties" to mapOf("name" to mapOf("type" to "string")),
                "required" to listOf("name"),
                "x-wow-class" to Tag::class.java.name,
            ),
        )
        val items = (defOf(completed, CartState::class.java)["properties"] as Map<*, *>)["items"]
        items.assert().isEqualTo(
            mapOf("type" to "object", "additionalProperties" to mapOf("type" to "integer", "format" to "int32")),
        )
        completed.defs.keys.assert().containsExactly(
            AddItem::class.java.name,
            CartState::class.java.name,
            Tag::class.java.name
        )
    }

    /** A command of another context a saga derives, which only its schema reference names in this Manifest. */
    data class ReserveStock(val quantity: Int)

    @Test
    fun `a command a subscription refers to by its schema should get its schema too`() {
        val saga = SubscriptionManifest(
            name = "OrderSaga.onPlaced",
            kind = SubscriptionKind.SAGA,
            source = SubscriptionSource(),
            function = FunctionManifest(FunctionKind.SAGA, "shop", "OrderSaga", "onPlaced"),
            derives = listOf("shop.cart.AddItem", schemaRef(ReserveStock::class.java.name)),
        )
        val fragment = fragment()
        val withSaga = fragment.copyWith(contexts = fragment.contexts.map { it.copyWith(subscriptions = listOf(saga)) })
        val completed = introspector.complete(withSaga)
        completed.unresolvedSchemaRefs.assert().isEmpty()
        defOf(completed, ReserveStock::class.java)["required"].assert().isEqualTo(listOf("quantity"))
    }

    @Test
    fun `the state fingerprint should be the kernel's`() {
        val completed = introspector.complete(fragment())
        val model = AggregateModel(
            AggregateType("shop", "cart"),
            CartState::class.java,
            { CartState(it.id) },
            emptyMap(),
            emptyMap(),
            emptyList(),
            emptyList(),
        )
        completed.contexts.single().aggregates.single().state.fingerprint.assert()
            .isEqualTo(EventCodec().stateSchemaFingerprint(model))
    }

    @Test
    fun `an override should replace the schema of a type Jackson writes with a custom serializer`() {
        val properties = defOf(introspector.complete(fragment()), AddItem::class.java)["properties"] as Map<*, *>
        properties["price"].assert().isEqualTo(
            mapOf("type" to "string", "pattern" to "^[0-9]+$", "x-wow-class" to Money::class.java.name),
        )
    }

    @Test
    fun `a type Jackson does not write as an object should need an override`() {
        assertThrows<IllegalStateException> { SchemaIntrospector(javaClass.classLoader).complete(fragment()) }
            .message.assert().contains("Jackson does not write ${Money::class.java.name} as an object")
            .contains("schemaOverrides")
    }

    @Test
    fun `an override that is not a JSON object should be refused`() {
        assertThrows<IllegalArgumentException> {
            SchemaIntrospector(javaClass.classLoader, mapOf(Money::class.java.name to "[]")).complete(fragment())
        }
    }

    @Test
    fun `a Java class should have no required or nullable property`() {
        val java = fragment().let { fragment ->
            val aggregate = fragment.contexts.single().aggregates.single()
            val commands = listOf(CommandManifest("Ship", schema = schemaRef(JavaShipment::class.java.name)))
            fragment.copyWith(
                contexts = listOf(
                    fragment.contexts.single().copyWith(aggregates = listOf(aggregate.copyWith(commands = commands)))
                )
            )
        }
        val shipment = defOf(introspector.complete(java), JavaShipment::class.java)
        shipment.containsKey("required").assert().isFalse()
        val properties = shipment["properties"] as Map<*, *>
        properties["carrier"].assert().isEqualTo(mapOf("type" to "string"))
        properties["parcels"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to "integer", "format" to "int32")),
        )
        (properties["address"] as Map<*, *>)["type"].assert().isEqualTo("object")
    }

    @Test
    fun `schemas already in the fragment should be kept`() {
        val known = fragment().copyWith(defs = mapOf(AddItem::class.java.name to mapOf("type" to "object")))
        val completed = introspector.complete(known)
        completed.defs[AddItem::class.java.name].assert().isEqualTo(mapOf("type" to "object"))
    }

    @Test
    fun `a state's non-null properties should be required, its nullable ones optional`() {
        val state = defOf(introspector.complete(fragment()), CartState::class.java)
        // id: a constructor parameter without a default; items: a property of the body, always written.
        (state["required"] as List<*>).assert().containsExactlyInAnyOrder("id", "items")
        (state["properties"] as Map<*, *>)["owner"].assert().isEqualTo(mapOf("type" to listOf("string", "null")))
    }

    @Description("A shipment of the order")
    @Summary("Shipment")
    data class Ship(val carrier: String)

    @Summary("  Shipped  ")
    data class Shipped(val carrier: String)

    @Description(" ")
    data class Track(val carrier: String)

    /** Its KDoc is the compiler's to read: the fragment entry carries what it says. */
    data class Deliver(val carrier: String)

    @Test
    fun `schemas should be described by their annotations, and commands and events by their entries`() {
        val aggregate = fragment().contexts.single().aggregates.single().copyWith(
            commands = listOf(
                CommandManifest("Ship", schema = schemaRef(Ship::class.java.name)),
                CommandManifest("Track", schema = schemaRef(Track::class.java.name)),
                CommandManifest("Deliver", schema = schemaRef(Deliver::class.java.name), description = "Delivers."),
            ),
            events = listOf(EventManifest("Shipped", schema = schemaRef(Shipped::class.java.name))),
        )
        val fragment = fragment().copyWith(contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate))))
        val completed = introspector.complete(fragment)
        defOf(completed, Ship::class.java)["description"].assert().isEqualTo("A shipment of the order")
        defOf(completed, Shipped::class.java)["description"].assert().isEqualTo("Shipped")
        defOf(completed, Track::class.java).containsKey("description").assert().isFalse()
        defOf(completed, Deliver::class.java)["description"].assert().isEqualTo("Delivers.")
        defOf(completed, CartState::class.java).containsKey("description").assert().isFalse()
    }

    @Test
    fun `the body of each lifecycle command an aggregate lists should be a definition keyed by its name`() {
        val aggregate = fragment().contexts.single().aggregates.single()
            .let { cart ->
                cart.copyWith(
                    commands = cart.commands + (Lifecycle.COMMANDS.keys.sorted() + "wow.lifecycle.Unknown").map {
                        SystemCommands.lifecycle("shop", cart.name, it)
                    },
                )
            }
        val fragment = fragment().copyWith(contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate))))
        val defs = introspector.complete(fragment).defs
        val lifecycleKeys = defs.keys.filter { it.startsWith("wow.") }
        lifecycleKeys.assert().containsExactlyInAnyOrder(*Lifecycle.COMMANDS.keys.toTypedArray())
        defs[Lifecycle.TRANSFER_OWNER].assert().isEqualTo(
            mapOf(
                "type" to "object",
                "properties" to mapOf("ownerId" to mapOf("type" to "string")),
                "required" to listOf("ownerId"),
                "description" to "Gives the aggregate to the owner ownerId.",
                "x-wow-class" to Lifecycle.COMMANDS.getValue(Lifecycle.TRANSFER_OWNER).name,
            ),
        )
        defs[Lifecycle.APPLY_TAGS].assert().isEqualTo(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tags" to mapOf(
                        "type" to "object",
                        "additionalProperties" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    ),
                ),
                "required" to listOf("tags"),
                "description" to "Sets the aggregate's tags, replacing the ones it had; empty tags clear them.",
                "x-wow-class" to Lifecycle.COMMANDS.getValue(Lifecycle.APPLY_TAGS).name,
            ),
        )
        (defs[Lifecycle.DELETE_AGGREGATE] as Map<*, *>)["type"].assert().isEqualTo("object")
        // A body the fragment already holds is kept.
        val known = fragment.copyWith(defs = mapOf(Lifecycle.DELETE_AGGREGATE to mapOf("type" to "object")))
        introspector.complete(known).defs[Lifecycle.DELETE_AGGREGATE].assert().isEqualTo(mapOf("type" to "object"))
    }

    @Test
    fun `the bodies of the snapshot rebuild and the job commands should be definitions keyed by their names`() {
        val aggregate = fragment().contexts.single().aggregates.single()
            .let { cart -> cart.copyWith(commands = cart.commands + SystemCommands.rebuild("shop", cart.name)) }
        val context = ContextManifest("shop", aggregates = listOf(aggregate), commands = SystemCommands.jobs("shop"))
        val defs = introspector.complete(fragment().copyWith(contexts = listOf(context))).defs
        SystemCommands.REBUILD.assert().isEqualTo(SnapshotRebuilds.REBUILD)
        defs.keys.filter { it.startsWith("wow.") }.assert()
            .containsExactlyInAnyOrder(SystemCommands.REBUILD, *SystemCommands.JOBS.toTypedArray())
        (defs[SystemCommands.REBUILD] as Map<*, *>)["required"].assert().isEqualTo(listOf("ids"))
    }

    /** A state of every value a plain JSON type does not tell apart. */
    class WireState(val id: String) {
        var count: Int = 0
        var small: Short = 0
        var big: Long = 0
        var ratio: Float = 0f
        var exact: Double = 0.0
        var amount: BigDecimal = BigDecimal.ZERO
        var huge: BigInteger = BigInteger.ZERO
        var at: Instant = Instant.EPOCH
        var offsetAt: OffsetDateTime? = null
        var localAt: LocalDateTime? = null
        var day: LocalDate? = null
        var clock: LocalTime? = null
        var lasting: Duration? = null
        var uuid: UUID? = null
        var codes: Set<String> = emptySet()
        var color: Color = Color.RED
        var tag: Tag? = null
        var initial: Char = 'a'
    }

    @Test
    fun `a schema should say the format, the set and the class its JSON type does not`() {
        val fragment = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "wire",
                    aggregates = listOf(
                        AggregateManifest(name = "wire", state = StateManifest(schemaRef(WireState::class.java.name))),
                    ),
                ),
            ),
        )
        val state = defOf(introspector.complete(fragment), WireState::class.java)
        state["x-wow-class"].assert().isEqualTo(WireState::class.java.name)
        val properties = state["properties"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        fun property(name: String) = properties[name] as Map<String, Any?>
        mapOf(
            "count" to "int32",
            "small" to "int32",
            "big" to "int64",
            "ratio" to "float",
            "amount" to "decimal",
            "huge" to "bigint",
            "at" to "date-time",
            "offsetAt" to "date-time",
            "day" to "date",
            "lasting" to "duration",
            "uuid" to "uuid",
        ).forEach { (name, format) -> property(name)["format"].assert().describedAs(name).isEqualTo(format) }
        property("exact").assert().isEqualTo(mapOf("type" to "number"))
        // The class is named where the type and the format do not name it.
        listOf("count", "big", "ratio", "amount", "huge", "at", "day", "lasting", "uuid", "codes").forEach {
            property(it).assert().describedAs(it).doesNotContainKey("x-wow-class")
        }
        mapOf(
            "small" to "java.lang.Short",
            "offsetAt" to "java.time.OffsetDateTime",
            "localAt" to "java.time.LocalDateTime",
            "clock" to "java.time.LocalTime",
            "initial" to "java.lang.Character",
            "tag" to Tag::class.java.name,
        ).forEach { (name, type) -> property(name)["x-wow-class"].assert().describedAs(name).isEqualTo(type) }
        property("codes").assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to "string"), "uniqueItems" to true),
        )
        property("color").assert().isEqualTo(
            mapOf("type" to "string", "enum" to listOf("RED", "GREEN"), "x-wow-class" to Color::class.java.name),
        )
    }
}
