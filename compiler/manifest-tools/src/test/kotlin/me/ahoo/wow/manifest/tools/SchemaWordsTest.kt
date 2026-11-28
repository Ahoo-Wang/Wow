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

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.moneySchema
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import org.junit.jupiter.api.Test

/**
 * The words of a schema (#7262): `@Summary` is the `title` and `@Description` the `description`, of a type and of a
 * property, wherever each language lets the two annotations stand.
 */
class SchemaWordsTest {
    @Summary("Price")
    @Description("An amount in cents.")
    data class Price(val cents: Long)

    @Summary("  Order line  ")
    @Description("  One line of an order.  ")
    data class Line(
        @Summary("SKU") @Description("The stock keeping unit.") val sku: String,
        @field:Summary("Quantity") @field:Description("How many are ordered.") val quantity: Int = 1,
        @Summary("Unit price") val price: Price,
        @Description("What the buyer saves.") val discount: Price?,
        val listPrice: Price,
        @Description("What it ships in.") val parcel: JavaParcel,
        val box: JavaParcel.Box,
        @Summary("Tags") @Description("Its tags.") val tags: List<String> = emptyList(),
        @Summary("Prices") @Description("By region.") val prices: Map<String, Price> = emptyMap(),
        @Summary("Sizes") val sizes: IntArray = IntArray(0),
        @Summary(" ") @Description("  Trimmed.  ") val blank: String = "",
        @Summary("Only a summary") val summarized: String = "",
        @Summary("Paid") @Description("What was paid.") val paid: SchemaIntrospectorTest.Money? = null,
        @Summary("Deposit") @Description("Declared here.") val deposit: SchemaIntrospectorTest.Money? = null,
    ) {
        @Summary("Note")
        @Description("A note of the buyer.")
        var note: String? = null

        @Summary("Total")
        @Description("The price of the line.")
        val total: Long get() = price.cents * quantity
    }

    class Order(val line: Line)

    private val overrides = mapOf(
        SchemaIntrospectorTest.Money::class.java.name to moneySchema,
        "${Line::class.java.name}#deposit" to """{"type":"string","description":"As the override says."}""",
    )

    private fun complete(state: Class<*>): Manifest =
        SchemaIntrospector(javaClass.classLoader, overrides).complete(stateFragment(state))

    private fun properties(schema: Any?): Map<*, *> = (schema as Map<*, *>)["properties"] as Map<*, *>

    private val order: Manifest by lazy { complete(Order::class.java) }

    private val line: Map<*, *> by lazy { properties(defOf(order, Order::class.java))["line"] as Map<*, *> }

    private fun property(name: String): Map<*, *> = properties(line)[name] as Map<*, *>

    private fun words(schema: Map<*, *>): Pair<Any?, Any?> = schema["title"] to schema["description"]

    @Test
    fun `a type's summary should be its title beside its description, both trimmed`() {
        words(line).assert().isEqualTo("Order line" to "One line of an order.")
        words(defOf(order, Price::class.java)).assert().isEqualTo("Price" to "An amount in cents.")
    }

    @Test
    fun `a Kotlin property's words should be its schema's, declared in the constructor, on its field or in the body`() {
        property("sku").assert().isEqualTo(
            mapOf("type" to "string", "title" to "SKU", "description" to "The stock keeping unit."),
        )
        words(property("quantity")).assert().isEqualTo("Quantity" to "How many are ordered.")
        words(property("note")).assert().isEqualTo("Note" to "A note of the buyer.")
    }

    @Test
    fun `a computed property's words should be its schema's`() {
        property("total").assert().isEqualTo(
            mapOf(
                "type" to "integer",
                "format" to "int64",
                "title" to "Total",
                "description" to "The price of the line.",
                "readOnly" to true,
            ),
        )
    }

    @Test
    fun `a Java record component's and a Java field's words should be their schemas'`() {
        words(properties(property("parcel"))["weight"] as Map<*, *>).assert().isEqualTo("Weight" to "In grams.")
        words(properties(property("parcel"))["label"] as Map<*, *>).assert().isEqualTo(null to null)
        words(properties(property("box"))["width"] as Map<*, *>).assert().isEqualTo("Width" to "In millimetres.")
    }

    @Test
    fun `a property's summary and description should not stand in for each other, and a blank one is left out`() {
        property("summarized").assert().isEqualTo(mapOf("type" to "string", "title" to "Only a summary"))
        property("blank").assert().isEqualTo(mapOf("type" to "string", "description" to "Trimmed."))
    }

    @Test
    fun `a property's words should stand beside the reference to its type, whose own stay in its definition`() {
        val price = schemaRef(Price::class.java.name)
        property("price").assert().isEqualTo(mapOf("\$ref" to price, "title" to "Unit price"))
        property("discount").assert().isEqualTo(
            mapOf(
                "anyOf" to listOf(mapOf("type" to "null"), mapOf("\$ref" to price)),
                "description" to "What the buyer saves."
            ),
        )
        property("listPrice").assert().isEqualTo(mapOf("\$ref" to price))
    }

    @Test
    fun `a property's words should replace those of a type written in place, keyword by keyword`() {
        val parcel = property("parcel")
        parcel.containsKey("allOf").assert().isFalse()
        parcel["type"].assert().isEqualTo("object")
        // The description is the property's; it declares no summary, so the title is still its type's.
        words(parcel).assert().isEqualTo("Parcel" to "What it ships in.")
        // A property without words is its type's schema, as it was.
        words(property("box")).assert().isEqualTo(null to null)
    }

    @Test
    fun `a container's words should be the property's, not each item's`() {
        property("tags").assert().isEqualTo(
            mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "title" to "Tags",
                "description" to "Its tags."
            ),
        )
        // A primitive array's items are a member of their own to the generator, which asks the property's words of it.
        property("sizes").assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to "integer", "format" to "int32"), "title" to "Sizes"),
        )
        property("prices").assert().isEqualTo(
            mapOf(
                "type" to "object",
                "additionalProperties" to mapOf("\$ref" to schemaRef(Price::class.java.name)),
                "title" to "Prices",
                "description" to "By region.",
            ),
        )
    }

    @Test
    fun `a property of a declared schema should carry its words, and a declared property keep its own`() {
        words(property("paid")).assert().isEqualTo("Paid" to "What was paid.")
        property("paid")["pattern"].assert().isEqualTo("^[0-9]+$")
        words(property("deposit")).assert().isEqualTo("Deposit" to "As the override says.")
    }

    @JsonClassDescription("Jackson's words of the class.")
    data class JacksonWords(
        @JsonPropertyDescription("Jackson's words.") val described: String,
        @Description("Wow's words.") @JsonPropertyDescription("Jackson's words.") val both: String,
        @Deprecated("Use both.") val old: String = "",
    )

    @Description("Wow's words of the class.")
    @JsonClassDescription("Jackson's words of the class.")
    data class BothWords(val value: String)

    class Described(val jackson: JacksonWords, val both: BothWords)

    @Test
    fun `Wow's words should come ahead of Jackson's, which describe what Wow's annotations do not`() {
        val described = properties(defOf(complete(Described::class.java), Described::class.java))
        val jackson = described["jackson"] as Map<*, *>
        words(jackson).assert().isEqualTo(null to "Jackson's words of the class.")
        words(properties(jackson)["described"] as Map<*, *>).assert().isEqualTo(null to "Jackson's words.")
        words(properties(jackson)["both"] as Map<*, *>).assert().isEqualTo(null to "Wow's words.")
        words(described["both"] as Map<*, *>).assert().isEqualTo(null to "Wow's words of the class.")
        // Kotlin's @Deprecated is no keyword of the schema.
        properties(jackson)["old"].assert().isEqualTo(mapOf("type" to "string"))
    }

    @Test
    fun `a conjunction that differs in more than words should keep both parts`() {
        val both = mapOf("allOf" to listOf(mapOf("type" to "integer", "minimum" to 1), mapOf("minimum" to 5)))
        SchemaWords.inPlace(both).assert().isEqualTo(both)
        val words = mapOf(
            "allOf" to listOf(mapOf("type" to "integer", "minimum" to 1, "title" to "A"), mapOf("minimum" to 1, "title" to "B")),
        )
        SchemaWords.inPlace(words).assert().isEqualTo(mapOf("type" to "integer", "minimum" to 1, "title" to "B"))
        // More parts than a type and a property's keywords, or keywords beside the conjunction: not the generator's.
        val three = mapOf("allOf" to listOf(mapOf("title" to "A"), mapOf("title" to "B"), mapOf("title" to "C")))
        SchemaWords.inPlace(three).assert().isEqualTo(three)
        val beside = mapOf("allOf" to listOf(mapOf("title" to "A"), mapOf("title" to "B")), "type" to "string")
        SchemaWords.inPlace(beside).assert().isEqualTo(beside)
        val schemaless = mapOf("allOf" to listOf(true, mapOf("title" to "B")))
        SchemaWords.inPlace(schemaless).assert().isEqualTo(schemaless)
    }

    @Summary("Ship")
    @Description("Ships the order.")
    data class Ship(val carrier: String)

    @Summary("Shipped")
    data class Shipped(val carrier: String)

    @Summary("Track")
    data class Track(val carrier: String)

    @Summary("Deliver")
    data class Deliver(val carrier: String)

    @Test
    fun `a command's and an event's description should be its entry's, unless that only repeats the title`() {
        val aggregate = AggregateManifest(
            name = "order",
            state = StateManifest(schemaRef(Order::class.java.name)),
            commands = listOf(
                // As the compiler writes them: the @Description, else the KDoc, else the @Summary.
                CommandManifest("Ship", schema = schemaRef(Ship::class.java.name), description = "Ships the order."),
                CommandManifest("Track", schema = schemaRef(Track::class.java.name), description = "Track"),
                CommandManifest(
                    "Deliver",
                    schema = schemaRef(Deliver::class.java.name),
                    description = "From the KDoc."
                ),
            ),
            events = listOf(
                EventManifest("Shipped", schema = schemaRef(Shipped::class.java.name), description = "Shipped")
            ),
        )
        val fragment = Manifest(contexts = listOf(ContextManifest("shop", aggregates = listOf(aggregate))))
        val completed = SchemaIntrospector(javaClass.classLoader, overrides).complete(fragment)
        words(defOf(completed, Ship::class.java)).assert().isEqualTo("Ship" to "Ships the order.")
        words(defOf(completed, Track::class.java)).assert().isEqualTo("Track" to null)
        defOf(completed, Track::class.java).containsKey("description").assert().isFalse()
        words(defOf(completed, Shipped::class.java)).assert().isEqualTo("Shipped" to null)
        words(defOf(completed, Deliver::class.java)).assert().isEqualTo("Deliver" to "From the KDoc.")
    }
}
