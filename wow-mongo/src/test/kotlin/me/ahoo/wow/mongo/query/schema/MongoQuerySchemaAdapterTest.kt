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

package me.ahoo.wow.mongo.query.schema

import com.mongodb.MongoNamespace
import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.ListCollectionsPublisher
import com.mongodb.reactivestreams.client.ListIndexesPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.AbstractMongoFilterCompiler
import me.ahoo.wow.mongo.query.mongoLogicalSchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.physicalField
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.serialization.JsonSerializer
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import tools.jackson.databind.node.StringNode
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

class MongoQuerySchemaAdapterTest {
    private val compiler = object : AbstractMongoFilterCompiler() {}

    @Test
    fun `bindings reuse the logical definition and retain masked raw capabilities`() {
        val rule = fullMaskRule()
        val value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), maskRule = rule)
        val definition = logical("state.secret" to value)
        val schema = MongoQuerySchemaAdapter.bind(definition, emptyList(), null)
        schema.definition.assert().isSameAs(definition)
        schema.field(QueryField("state.secret"))!!.let {
            it.value.assert().isSameAs(value)
            it.bindings.keys.assert().contains(QueryCapability.CURSOR_SORT, QueryCapability.EXACT_MATCH)
            it.value.maskRule.assert().isSameAs(rule)
        }
    }

    @Test
    fun `model identity bindings are native while response paths stay logical`() {
        listOf(QueryModel.SNAPSHOT to "aggregateId", QueryModel.EVENT_STREAM to "id").forEach { (model, id) ->
            val schema = MongoQuerySchemaAdapter.bind(logical(id to scalar()), emptyList(), null, model)
            schema.field(QueryField(id))!!.let {
                it.binding(QueryCapability.EXACT_MATCH)!!.physicalField.assert().isEqualTo(QueryField("_id"))
                it.projectionField.assert().isEqualTo(QueryField("_id"))
                it.responseField.assert().isEqualTo(QueryField(id))
            }
        }
    }

    @Test
    fun `numeric unions retain all native storage types and comparable cursor capability`() {
        val numeric = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalar(QueryValueType.INTEGER), scalar(QueryValueType.DECIMAL))
        )
        val types = listOf("int", "long", "double", "decimal")
        val schema = bind("amount", numeric, Document("bsonType", types))
        schema.field(QueryField("amount"))!!.let {
            it.bindings.keys.assert().contains(
                QueryCapability.EXACT_MATCH,
                QueryCapability.RANGE,
                QueryCapability.CURSOR_SORT
            )
            it.binding(QueryCapability.CURSOR_SORT)!!.storageTypes!!.map { type -> type.value }.assert()
                .containsExactlyInAnyOrder(*types.toTypedArray())
        }
    }

    @Test
    fun `scalar and primitive array unions prove each native operand domain`() {
        val value = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalar(), array(scalar(QueryValueType.INTEGER)))
        )
        val validator = Document(
            "anyOf",
            listOf(
                Document("bsonType", "string"),
                Document("bsonType", "array").append("items", Document("bsonType", "long"))
            )
        )
        val schema = bind("value", value, validator)
        schema.field(QueryField("value"))!!.bindings.keys.assert()
            .contains(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)
            .doesNotContain(QueryCapability.CURSOR_SORT, QueryCapability.ELEMENT_SCOPE)
        val arrays = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(array(scalar(QueryValueType.INTEGER)), array(scalar(QueryValueType.DECIMAL)))
        )
        bind(
            "values",
            arrays,
            Document("bsonType", "array").append("items", Document("bsonType", listOf("int", "double")))
        )
            .field(
                QueryField("values")
            )!!.bindings.keys.assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)
    }

    @Test
    fun `cursor sorting rejects mixed BSON families independently of ordinary sort`() {
        val value = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalar(), scalar(QueryValueType.INTEGER))
        )
        val schema = bind("mixed", value, Document("bsonType", listOf("string", "int")))
        schema.field(QueryField("mixed"))!!.bindings.keys.assert()
            .contains(QueryCapability.SORT).doesNotContain(QueryCapability.CURSOR_SORT)
    }

    @Test
    fun `unknown union branch cannot gain operand capabilities`() {
        val value = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(scalar(), QueryValueSchema(QueryValueKind.UNKNOWN))
        )
        bind("value", value).field(QueryField("value"))!!.bindings.keys.assert()
            .containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `primitive array operations consume direct items and preserve container storage`() {
        val schema = bind(
            "scores",
            array(scalar(QueryValueType.INTEGER)),
            Document("bsonType", "array").append("items", Document("bsonType", "long"))
        )
        schema.field(QueryField("scores"))!!.let {
            it.value.valueTypes.assert().isEmpty()
            it.bindings.keys.assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)
                .doesNotContain(QueryCapability.ELEMENT_SCOPE, QueryCapability.CURSOR_SORT)
            it.binding(QueryCapability.RANGE)!!.storageTypes!!.single().value.assert().isEqualTo("array")
        }
        val nested = bind("scores", array(array(scalar(QueryValueType.INTEGER))))
        nested.field(QueryField("scores"))!!.bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `array operands are validated against direct items before native compilation`() {
        val schema = bind(
            "scores",
            array(scalar(QueryValueType.INTEGER)),
            Document("bsonType", "array").append("items", Document("bsonType", "long"))
        )
        val accepted = EqualFilter(QueryField("scores"), JsonSerializer.valueToTree(listOf(1, 2)))
        compiler.compile(validateQuery(accepted, schema), schema).toBsonDocument().assert()
            .isEqualTo(Filters.eq("scores", listOf(1, 2)).toBsonDocument())
        assertThrows<QuerySchemaValidationException> {
            validateQuery(EqualFilter(QueryField("scores"), JsonSerializer.valueToTree(listOf(1, "bad"))), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(EqualFilter(QueryField("scores"), JsonSerializer.valueToTree(1.5)), schema)
        }
    }

    @Test
    fun `all logical and native array ancestors prevent cursor sorting`() {
        val address = obj("city" to scalar())
        val schema = bind("addresses", array(address))
        schema.field(QueryField("addresses.city"))!!.let {
            it.elementAncestors.assert().isEqualTo(listOf(QueryField("addresses")))
            it.bindings.keys.assert().doesNotContain(QueryCapability.CURSOR_SORT)
        }
        val sparse = MongoQuerySchemaAdapter.bind(
            logical("addresses.city" to scalar()),
            emptyList(),
            Document(
                "properties",
                Document(
                    "addresses",
                    Document(
                        "bsonType",
                        "array"
                    ).append("items", Document("properties", Document("city", Document("bsonType", "string"))))
                )
            ),
        )
        sparse.field(QueryField("addresses.city"))!!.bindings.keys.assert().doesNotContain(QueryCapability.CURSOR_SORT)
    }

    @Test
    fun `map of object lists compiles exact relative element paths and rejects unknown suffixes`() {
        val homes = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = array(obj("city" to scalar())))
        val schema = bind("homes", homes)
        val filter = ElementMatchFilter(
            QueryField("homes.home"),
            EqualFilter(QueryField("city"), StringNode.valueOf("Paris"))
        )
        compiler.compile(filter, schema).toBsonDocument().assert().isEqualTo(
            Filters.elemMatch("homes.home", Filters.eq("city", "Paris")).toBsonDocument(),
        )
        schema.field(QueryField("homes.home.city.extra")).assert().isNull()
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                ElementMatchFilter(
                    QueryField("homes.home"),
                    EqualFilter(QueryField("city.extra"), StringNode.valueOf("Paris"))
                ),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(EqualFilter(QueryField("homes.home.city"), StringNode.valueOf("Paris")), schema)
        }
    }

    @Test
    fun `typed additional properties retain native facts and named overrides cannot fall back`() {
        val homes = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = array(obj("city" to scalar())))
        val list = Document(
            "bsonType",
            "array"
        ).append(
            "items",
            Document("bsonType", "object").append("properties", Document("city", Document("bsonType", "string")))
        )
        val overridden = Document(
            "bsonType",
            "array"
        ).append(
            "items",
            Document("bsonType", "object").append("properties", Document("city", Document("bsonType", "int")))
        )
        val schema = bind(
            "homes",
            homes,
            Document(
                "bsonType",
                "object"
            ).append("additionalProperties", list).append("properties", Document("home", overridden))
        )
        schema.field(
            QueryField("homes.work.city")
        )!!.binding(QueryCapability.EXACT_MATCH)!!.storageTypes!!.single().value.assert().isEqualTo("string")
        schema.field(QueryField("homes.home.city"))!!.bindings.keys.assert().doesNotContain(QueryCapability.EXACT_MATCH)
        assertThrows<QuerySchemaValidationException> {
            schema.physicalField(QueryField("city"), QueryCapability.EXACT_MATCH, QueryField("homes.home"))
        }
    }

    @Test
    fun `explicit native map key does not inherit default validator constraints for missing descendants`() {
        val value = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = obj("city" to scalar()))
        val fallback = Document(
            "bsonType",
            "object"
        ).append("properties", Document("city", Document("bsonType", "string")))
        val schema = bind(
            "homes",
            value,
            Document("bsonType", "object").append("additionalProperties", fallback)
                .append("properties", Document("home", Document("bsonType", "object")))
        )
        schema.field(
            QueryField("homes.work.city")
        )!!.binding(QueryCapability.EXACT_MATCH)!!.storageTypes!!.single().value.assert().isEqualTo("string")
        schema.field(
            QueryField("homes.home.city")
        )!!.binding(QueryCapability.EXACT_MATCH)!!.storageTypes.assert().isNull()
    }

    @Test
    fun `map container never grants map values its own object type or capabilities`() {
        val map = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = scalar(QueryValueType.INTEGER))
        val schema = bind("counts", map)
        schema.field(QueryField("counts"))!!.bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
        schema.field(QueryField("counts.home"))!!.value.valueTypes.assert().containsExactly(QueryValueType.INTEGER)
        schema.field(QueryField("counts.home"))!!.bindings.keys.assert().contains(QueryCapability.RANGE)
        schema.field(QueryField("counts.home.extra")).assert().isNull()
    }

    @Test
    fun `named nested arrays preserve both scopes while unnamed arrays are not element scopes`() {
        val schema = bind("orders", array(obj("lines" to array(obj("sku" to scalar())))))
        val filter = ElementMatchFilter(
            QueryField("orders"),
            ElementMatchFilter(QueryField("lines"), EqualFilter(QueryField("sku"), StringNode.valueOf("one")))
        )
        compiler.compile(filter, schema).toBsonDocument().assert().isEqualTo(
            Filters.elemMatch("orders", Filters.elemMatch("lines", Filters.eq("sku", "one"))).toBsonDocument(),
        )
        bind("orders", array(array(obj("sku" to scalar())))).field(QueryField("orders"))!!.bindings.keys.assert()
            .doesNotContain(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `native date operands stay unsupported while date aggregation and cursor families remain available`() {
        listOf("date", "timestamp").forEach { native ->
            val schema = bind("created", scalar(semantic = Temporal.Date), Document("bsonType", listOf(native, "null")))
            schema.field(QueryField("created"))!!.bindings.keys.assert()
                .contains(QueryCapability.AGGREGATE_TEMPORAL, QueryCapability.CURSOR_SORT)
                .doesNotContain(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)
        }
        bind("created", scalar(semantic = Temporal.Date)).field(QueryField("created"))!!.bindings.keys.assert()
            .doesNotContain(QueryCapability.EXACT_MATCH, QueryCapability.RANGE, QueryCapability.AGGREGATE_TEMPORAL)
        bind("created", scalar(semantic = Temporal.Date), Document("bsonType", listOf("date", "timestamp")))
            .field(QueryField("created"))!!.bindings.keys.assert().doesNotContain(QueryCapability.CURSOR_SORT)
    }

    @Test
    fun `epoch and formatted semantics live on array items`() {
        val epoch = Temporal.Epoch(TimeUnit.SECONDS)
        val schema = bind(
            "times",
            array(scalar(QueryValueType.INTEGER, epoch)),
            Document("bsonType", "array").append("items", Document("bsonType", "long"))
        )
        schema.field(QueryField("times"))!!.let {
            it.value.items!!.semanticType.assert().isEqualTo(epoch)
            it.bindings.keys.assert().contains(QueryCapability.RANGE, QueryCapability.AGGREGATE_TEMPORAL)
        }
    }

    @Test
    fun `composed validator constraints retain nullable and unknown alternatives`() {
        val nullable = Document("anyOf", listOf(Document("bsonType", "string"), Document("bsonType", "null")))
        bind("name", scalar(), nullable).field(QueryField("name"))!!.binding(QueryCapability.EXACT_MATCH)!!
            .storageTypes!!.single().value.assert().isEqualTo("string")
        val uncertain = Document("anyOf", listOf(Document("bsonType", "string"), Document()))
        bind(
            "name",
            scalar(),
            uncertain
        ).field(QueryField("name"))!!.bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
        val itemUnion = Document(
            "bsonType",
            "array"
        ).append("items", Document("anyOf", listOf(Document("bsonType", "int"), Document("bsonType", "long"))))
        bind(
            "values",
            array(scalar(QueryValueType.INTEGER)),
            itemUnion
        ).field(QueryField("values"))!!.bindings.keys.assert().contains(QueryCapability.RANGE)
    }

    @Test
    fun `numeric family alias intersects concrete BSON types before capability proof`() {
        listOf("int", "long").forEach { type ->
            val constraints = listOf(Document("bsonType", "number"), Document("bsonType", type))
            listOf(constraints, constraints.reversed()).forEach { branches ->
                val field = bind("value", scalar(QueryValueType.INTEGER), Document("allOf", branches))
                    .field(QueryField("value"))!!
                field.bindings.keys.assert().contains(QueryCapability.EXACT_MATCH)
                field.binding(
                    QueryCapability.EXACT_MATCH
                )!!.storageTypes!!.map { it.value }.assert().isEqualTo(listOf(type))
            }
        }
        val disjoint = Document("allOf", listOf(Document("bsonType", "number"), Document("bsonType", "string")))
        bind(
            "value",
            scalar(),
            disjoint
        ).field(QueryField("value"))!!.bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `conflicting containers suppress descendants without suppressing similarly named siblings`() {
        val definition = logical("items" to array(obj("name" to scalar())), "itemsExtra" to obj("name" to scalar()))
        val schema = MongoQuerySchemaAdapter.bind(
            definition,
            emptyList(),
            Document("properties", Document("items", Document("bsonType", "string")))
        )
        schema.field(QueryField("items.name"))!!.bindings.assert().isEmpty()
        schema.field(QueryField("itemsExtra.name"))!!.bindings.keys.assert().contains(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `text search requires a visible complete index`() {
        val text = Document("key", Document("all", "text"))
        MongoQuerySchemaAdapter.bind(
            logicalSchema(),
            listOf(text),
            null
        ).capabilities.assert().contains(QueryCapability.FULL_TEXT_TERMS)
        listOf(
            Document(text).append("hidden", true),
            Document(text).append("partialFilterExpression", Document("active", true))
        ).forEach {
            MongoQuerySchemaAdapter.bind(logicalSchema(), listOf(it), null).capabilities.assert().isEmpty()
        }
    }

    @Test
    fun `each metadata subscription rereads native index facts`() {
        val collection = mockk<MongoCollection<Document>>()
        every { collection.listIndexes() } returnsMany listOf(indexes(), indexes(Document("key", Document("all", "text"))))
        val schema = MongoQuerySchemaAdapter(collection).resolve(logicalSchema())
        schema.test().assertNext { it.capabilities.assert().isEmpty() }.verifyComplete()
        schema.test().assertNext { it.capabilities.assert().contains(QueryCapability.FULL_TEXT_TERMS) }.verifyComplete()
        verify(exactly = 2) { collection.listIndexes() }
        verify(exactly = 0) { collection.find(any<Bson>()) }
    }

    private fun logical(vararg fields: Pair<String, QueryValueSchema>) = mongoLogicalSchema(
        fields.associate {
            QueryField(it.first) to it.second
        }
    )
    private fun obj(
        vararg fields: Pair<String, QueryValueSchema>
    ) = QueryValueSchema(QueryValueKind.OBJECT, properties = fields.toMap())
    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)
    private fun scalar(type: QueryValueType = QueryValueType.STRING, semantic: Temporal? = null) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type), semanticType = semantic)
    private fun bind(path: String, value: QueryValueSchema, validator: Document? = null) = MongoQuerySchemaAdapter.bind(
        logical(path to value),
        emptyList(),
        validator?.let { Document("properties", Document(path, it)) },
    )
    private fun logicalSchema() = logical("aggregateId" to scalar())

    @Test
    fun `resolve should read indexes and validator without reading documents`() {
        val collection = mockk<MongoCollection<Document>>()
        val database = mockk<MongoDatabase>()
        val collectionFacts = collections(
            Document("name", "snapshots").append(
                "options",
                Document(
                    "validator",
                    Document(
                        "\$jsonSchema",
                        Document("bsonType", "object").append(
                            "properties",
                            Document("_id", Document("bsonType", "objectId")),
                        ),
                    ),
                ),
            ),
        )
        every { collection.namespace } returns MongoNamespace("wow", "snapshots")
        every { collection.listIndexes() } returns indexes(Document("key", Document("all", "text")))
        every { database.listCollections() } returns collectionFacts

        MongoQuerySchemaAdapter(collection, database).resolve(logicalSchema())
            .test()
            .assertNext { schema ->
                schema.field(QueryField("aggregateId"))!!
                    .bindings.getValue(QueryCapability.PRESENCE)
                    .storageTypes?.singleOrNull()?.value.assert().isEqualTo("objectId")
            }
            .verifyComplete()

        verify(exactly = 1) { collection.listIndexes() }
        verify(exactly = 1) { database.listCollections() }
        verify(exactly = 1) { collectionFacts.filter(any()) }
        verify(exactly = 0) { collection.find() }
        verify(exactly = 0) { collection.find(any<Bson>()) }
        verify(exactly = 0) { collection.aggregate(any<List<Bson>>()) }
    }

    @Test
    fun `resolve should wrap driver failures with their cause`() {
        val failure = IllegalStateException("indexes unavailable")
        val collection = mockk<MongoCollection<Document>>()
        every { collection.namespace } returns MongoNamespace("wow", "snapshots")
        every { collection.listIndexes() } returns failingIndexes(failure)

        MongoQuerySchemaAdapter(collection).resolve(logicalSchema())
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
                error.cause.assert().isSameAs(failure)
            }
            .verify()
    }

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private data class Masked(@field:Mask val secret: String)

    private fun indexes(vararg values: Document): ListIndexesPublisher<Document> = mockk {
        every { subscribe(any()) } answers {
            Flux.fromIterable(values.toList()).subscribe(firstArg<Subscriber<in Document>>())
        }
    }

    private fun failingIndexes(failure: Throwable): ListIndexesPublisher<Document> = mockk {
        every { subscribe(any()) } answers {
            Flux.error<Document>(failure).subscribe(firstArg<Subscriber<in Document>>())
        }
    }

    private fun collections(vararg values: Document): ListCollectionsPublisher<Document> {
        val publisher = mockk<ListCollectionsPublisher<Document>>()
        every { publisher.filter(any()) } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.fromIterable(values.toList()).subscribe(firstArg<Subscriber<in Document>>())
        }
        return publisher
    }
}
