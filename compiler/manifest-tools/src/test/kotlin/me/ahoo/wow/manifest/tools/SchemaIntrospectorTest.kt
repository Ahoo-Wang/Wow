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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
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
import me.ahoo.wow.spi.json.StateJson
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

    data class Reading(val value: Long)

    /** Containers whose elements or values may be null, nested and not, beside ones whose may not. */
    data class ContainersState(
        val nullableItems: List<Long?>,
        val nullableValues: Map<String, String?>,
        val values: Map<String, Long>,
        val sameValues: Map<String, Long>,
        val itemsOrNull: List<Long>?,
        val nullableReadings: List<Map<String, Reading?>>,
        val reading: Reading,
        val codes: Set<String?>,
    )

    /** Arrays, as Jackson writes them: an object array's elements nullable as declared, a primitive one's never. */
    class ArraysState(val names: Array<String?>, val counts: IntArray)

    @Test
    fun `a container should be written at each use, its elements nullable as their Kotlin type arguments say`() {
        val fragment = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "box",
                            state = StateManifest(schemaRef(ContainersState::class.java.name)),
                        ),
                        AggregateManifest(
                            name = "shelf",
                            state = StateManifest(schemaRef(ArraysState::class.java.name)),
                        ),
                    ),
                ),
            ),
        )
        val completed = introspector.complete(fragment)
        val properties = defOf(completed, ContainersState::class.java)["properties"] as Map<*, *>
        val long = mapOf("type" to "integer", "format" to "int64")
        val nullableLong = mapOf("type" to listOf("integer", "null"), "format" to "int64")
        properties["nullableItems"].assert().isEqualTo(mapOf("type" to "array", "items" to nullableLong))
        properties["nullableValues"].assert().isEqualTo(
            mapOf("type" to "object", "additionalProperties" to mapOf("type" to listOf("string", "null"))),
        )
        // A map used twice is written at each use: no definition is keyed by its erased type.
        listOf("values", "sameValues").forEach {
            properties[it].assert().isEqualTo(mapOf("type" to "object", "additionalProperties" to long))
        }
        properties["itemsOrNull"].assert().isEqualTo(mapOf("type" to listOf("array", "null"), "items" to long))
        // A nullable class is a null alternative beside its reference, as on a nullable property.
        properties["nullableReadings"].assert().isEqualTo(
            mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to mapOf(
                        "anyOf" to listOf(mapOf("type" to "null"), mapOf("\$ref" to schemaRef(Reading::class.java.name))),
                    ),
                ),
            ),
        )
        properties["codes"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to listOf("string", "null")), "uniqueItems" to true),
        )
        val arrays = defOf(completed, ArraysState::class.java)["properties"] as Map<*, *>
        arrays["names"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to listOf("string", "null"))),
        )
        arrays["counts"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to "integer", "format" to "int32")),
        )
        completed.defs.keys.assert().containsExactlyInAnyOrder(
            ArraysState::class.java.name,
            ContainersState::class.java.name,
            Reading::class.java.name,
        )
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(Shape.Circle::class, name = "circle"),
        JsonSubTypes.Type(Shape.Square::class, name = "square"),
    )
    sealed interface Shape {
        data class Circle(val radius: Int) : Shape

        data class Square(val side: Int) : Shape
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(JsonSubTypes.Type(Card::class, name = "card"), JsonSubTypes.Type(Transfer::class, name = "transfer"))
    interface Payment

    data class Card(val last4: String) : Payment

    data class Transfer(val iban: String) : Payment

    class PlanState(val id: String) {
        var main: Shape = Shape.Circle(1)
        var spare: Shape? = null
        var shapes: List<Shape> = emptyList()
        var payment: Payment? = null
    }

    /** A command's Jakarta constraints, as an api module writes them on its fields (`@field:`). */
    data class Constrained(
        @field:NotBlank val name: String,
        @field:Positive val price: BigDecimal,
        @field:Min(1) @field:Max(9) val count: Int,
        @field:Size(min = 1, max = 3) val tags: List<String>,
        @field:Pattern(regexp = "^[a-z]+$") val code: String,
        @field:Email val email: String?,
        // A constraint decides no requiredness: the Kotlin type does, so this one stays optional and nullable.
        @field:NotNull val note: String? = null,
    )

    @Test
    fun `a member's Jakarta constraints should be the validation keywords of its schema, not its requiredness`() {
        val schema = defOf(stateFragment(Constrained::class.java).let(introspector::complete), Constrained::class.java)
        // Each property as text: a bound is a number of the generator's choosing, an Int or a BigDecimal.
        val properties = (schema["properties"] as Map<*, *>).mapValues { (_, property) ->
            (property as Map<*, *>).mapValues { it.value.toString() }
        }
        properties["name"].assert().isEqualTo(mapOf("type" to "string", "minLength" to "1"))
        properties["price"].assert()
            .isEqualTo(mapOf("type" to "number", "format" to "decimal", "exclusiveMinimum" to "0"))
        properties["count"].assert()
            .isEqualTo(mapOf("type" to "integer", "format" to "int32", "minimum" to "1", "maximum" to "9"))
        properties["tags"].assert()
            .isEqualTo(mapOf("type" to "array", "items" to "{type=string}", "minItems" to "1", "maxItems" to "3"))
        properties["code"].assert().isEqualTo(mapOf("type" to "string", "pattern" to "^[a-z]+$"))
        properties["email"].assert().isEqualTo(mapOf("type" to "[string, null]", "format" to "email"))
        properties["note"].assert().isEqualTo(mapOf("type" to "[string, null]"))
        (schema["required"] as List<*>).assert().containsExactlyInAnyOrder("name", "price", "count", "tags", "code")
    }

    /** A fragment of the one aggregate `plan` of [state]. */
    private fun stateFragment(state: Class<*>): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(AggregateManifest("plan", state = StateManifest(schemaRef(state.name)))),
            ),
        ),
    )

    @Test
    fun `a polymorphic type should be the definition of its subtypes, named by its base class`() {
        val completed = introspector.complete(stateFragment(PlanState::class.java))
        val shape = defOf(completed, Shape::class.java)
        shape["x-wow-class"].assert().isEqualTo(Shape::class.java.name)
        // Each subtype, with the discriminator Jackson writes it under.
        (shape["anyOf"] as List<*>).map { (it as Map<*, *>)["x-wow-class"] }.assert()
            .containsExactly(Shape.Circle::class.java.name, Shape.Square::class.java.name)
        (shape["anyOf"] as List<*>).map { alternative ->
            ((alternative as Map<*, *>)["allOf"] as List<*>).firstNotNullOf { part ->
                (((part as Map<*, *>)["properties"] as Map<*, *>)["kind"] as Map<*, *>?)?.get("const")
            }
        }.assert().containsExactly("circle", "square")
        // A property of it refers to its definition, nullable or in a list as any other.
        val properties = defOf(completed, PlanState::class.java)["properties"] as Map<*, *>
        val ref = mapOf("\$ref" to schemaRef(Shape::class.java.name))
        properties["main"].assert().isEqualTo(ref)
        properties["spare"].assert().isEqualTo(mapOf("anyOf" to listOf(mapOf("type" to "null"), ref)))
        properties["shapes"].assert().isEqualTo(mapOf("type" to "array", "items" to ref))
        // An interface's subtypes alike; referred to once, its definition is written where it is used.
        val payment = ((properties["payment"] as Map<*, *>)["anyOf"] as List<*>).map { it as Map<*, *> }
        payment.first().assert().isEqualTo(mapOf("type" to "null"))
        payment.last()["x-wow-class"].assert().isEqualTo(Payment::class.java.name)
        (payment.last()["anyOf"] as List<*>).map { (it as Map<*, *>)["x-wow-class"] }.assert()
            .containsExactly(Card::class.java.name, Transfer::class.java.name)
    }

    data class Entry(val sku: String, val quantity: Int, val price: Int) {
        val subtotal: Int get() = quantity * price
    }

    class LedgerState(val id: String) {
        var entries: List<Entry> = emptyList()
        var owner: String? = null

        /** A field's property whose getter the generator does not pair with its field (`isOpen()`): no computed one. */
        var isOpen: Boolean = true

        /** A public field and no getter: a property Jackson writes by the field alone. */
        @JvmField
        var note: String = ""
        val total: Int get() = entries.sumOf { it.subtotal }
        val empty: Boolean get() = entries.isEmpty()
        val initial: String? get() = owner?.take(1)
        val skus: List<String?> get() = entries.map { it.sku }

        /** Computed containers of a class: the generator reads their items through scopes of their own. */
        val multiples: List<Entry> get() = entries.filter { it.quantity > 1 }
        val bySku: Map<String, Entry> get() = entries.associateBy { it.sku }

        @get:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val contact: String get() = "owner:$owner"

        @get:JsonProperty("count")
        val entryCount: Int get() = entries.size

        @get:JsonIgnore
        val hidden: Int get() = 1

        @Suppress("FunctionOnlyReturningConstant")
        fun compute(): Int = 2

        fun entryOf(sku: String): Entry? = entries.firstOrNull { it.sku == sku }

        companion object {
            /** A static field of the computed property's name, the companion's: it backs no instance property. */
            @Suppress("unused")
            val total: Int = 0
        }
    }

    @Test
    fun `a computed property Jackson writes should be a read-only property of the schema`() {
        val completed = introspector.complete(stateFragment(LedgerState::class.java))
        val ledger = defOf(completed, LedgerState::class.java)
        val properties = ledger["properties"] as Map<*, *>
        // Every property Jackson writes into the snapshot, and none other: not a function, not an ignored getter.
        val written = StateJson.mapper.readTree(
            StateJson.mapper.writeValueAsString(LedgerState("l").apply { entries = listOf(Entry("mug", 2, 3)) }),
        ).propertyNames().asSequence().toSet()
        properties.keys.assert().isEqualTo(written)
        properties.keys.assert().doesNotContain("hidden", "compute", "entryCount", "entryOf")
        // A field's property is the field's, written once and not read-only, with or without a getter.
        (properties["isOpen"] as Map<*, *>).keys.assert().doesNotContain("readOnly")
        properties["note"].assert().isEqualTo(mapOf("type" to "string"))
        properties["total"].assert().isEqualTo(mapOf("type" to "integer", "format" to "int32", "readOnly" to true))
        properties["empty"].assert().isEqualTo(mapOf("type" to "boolean", "readOnly" to true))
        properties["count"].assert().isEqualTo(mapOf("type" to "integer", "format" to "int32", "readOnly" to true))
        properties["initial"].assert().isEqualTo(mapOf("type" to listOf("string", "null"), "readOnly" to true))
        properties["skus"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to listOf("string", "null")), "readOnly" to true),
        )
        // A computed container is read-only; its items are the class's, plain, not a property of their own.
        val entryRef = mapOf("\$ref" to schemaRef(Entry::class.java.name))
        properties["multiples"].assert()
            .isEqualTo(mapOf("type" to "array", "items" to entryRef, "readOnly" to true))
        properties["bySku"].assert()
            .isEqualTo(mapOf("type" to "object", "additionalProperties" to entryRef, "readOnly" to true))
        // Its query keywords, as a field's: a sensitive one is masked.
        (properties["contact"] as Map<*, *>)["x-wow-sensitive"].assert().isEqualTo(mapOf("level" to "confidential"))
        // Written always, so required unless nullable.
        (ledger["required"] as List<*>).assert()
            .containsExactlyInAnyOrder(
                "id",
                "entries",
                "isOpen",
                "note",
                "total",
                "empty",
                "skus",
                "multiples",
                "bySku",
                "contact",
                "count",
            )
        // Of a nested class too: the class, a definition, holds its own computed property, and is not read-only.
        val entry = defOf(completed, Entry::class.java)
        entry.keys.assert().doesNotContain("readOnly")
        ((entry["properties"] as Map<*, *>)["subtotal"] as Map<*, *>)["readOnly"].assert().isEqualTo(true)
        (entry["required"] as List<*>).assert().contains("subtotal")
        // The fingerprint was the shape Jackson writes all along: computed properties included.
        completed.contexts.single().aggregates.single().state.fingerprint.assert()
            .isEqualTo(StateJson.fingerprint(LedgerState::class.java))
    }

    class RoutedState(val id: String) {
        var target: AggregateRef? = null
        var source: AggregateRef? = null
    }

    @Test
    fun `a getter Wow's JSON does not write should be no computed property`() {
        val completed = introspector.complete(stateFragment(RoutedState::class.java))
        // Wow writes a ref with a serializer of its own, its derived isAssigned left out.
        val ref = completed.defs["${AggregateRef::class.java.name}-nullable"] as Map<*, *>
        (ref["properties"] as Map<*, *>).keys.assert().containsExactlyInAnyOrder("id", "tenantId", "type")
    }
}
