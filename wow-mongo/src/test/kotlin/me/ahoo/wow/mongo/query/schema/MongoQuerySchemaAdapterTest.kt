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
import com.mongodb.reactivestreams.client.FindPublisher
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
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.event.EventStreamFieldConverter
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackendFactory
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaResolution
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.StringNode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.jvm.javaField

@Suppress("LargeClass")
class MongoQuerySchemaAdapterTest {
    @Test
    fun `binding should retain a logical mask rule`() {
        val secret = QueryField("state.secret")
        val rule = fullMaskRule()

        val schema = MongoQuerySchemaAdapter.bind(
            LogicalQuerySchema(mapOf(secret to field(QueryValueType.STRING, maskRule = rule))),
            emptyList(),
            null,
        )

        schema.fields.getValue(secret).masked.assert().isTrue()
    }

    @Test
    fun `event stream schema should bind logical id to MongoDB document id`() {
        val id = QueryField("id")
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema = LogicalQuerySchema(mapOf(id to field(QueryValueType.STRING))),
            indexes = emptyList(),
            validatorSchema = null,
            model = QueryModel.EVENT_STREAM,
            fieldConverter = EventStreamFieldConverter,
        )

        schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
        schema.fields.getValue(id).let { fieldSchema ->
            fieldSchema.binding(QueryCapability.EXACT_MATCH)!!.let { binding ->
                binding.resolvedField.assert().isEqualTo(id)
                binding.physicalField.assert().isEqualTo(QueryField("_id"))
            }
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.NONE)
        }
    }

    @Test
    fun `element descendants should infer relative rewrites from absolute predicates`() {
        val orders = QueryField("state.orders")
        val lines = QueryField("state.orders.lines")
        val price = QueryField("state.orders.lines.price")
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema = LogicalQuerySchema(
                linkedMapOf(
                    orders to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                    lines to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                    price to field(QueryValueType.INTEGER),
                ),
            ),
            indexes = emptyList(),
            validatorSchema = null,
            model = QueryModel.SNAPSHOT,
            fieldConverter = FieldConverter { "document.$it" },
        )
        val relative = ElementMatchFilter(
            orders,
            ElementMatchFilter(QueryField("lines"), EqualFilter(QueryField("price"), IntNode.valueOf(10))),
        )
        val absolute = ElementMatchFilter(
            orders,
            ElementMatchFilter(lines, EqualFilter(price, IntNode.valueOf(10))),
        )

        schema.fields.getValue(price).let { fieldSchema ->
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
            fieldSchema.binding(QueryCapability.EXACT_MATCH)!!.let { binding ->
                binding.resolvedField.assert().isEqualTo(price)
                binding.physicalField.assert().isEqualTo(QueryField("document.state.orders.lines.price"))
            }
        }
        schema.resolve(relative).value.assert().isSameAs(relative)
        val resolved = schema.resolve(absolute).value as ElementMatchFilter
        val resolvedLines = resolved.predicate as ElementMatchFilter
        resolvedLines.field.assert().isEqualTo(QueryField("lines"))
        (resolvedLines.predicate as EqualFilter).field.assert().isEqualTo(QueryField("price"))
    }

    @Test
    fun `element dynamic descendants should retain relative rewrite context`() {
        val orders = QueryField("state.orders")
        val attributes = QueryField("state.orders.attributes")
        val color = QueryField("state.orders.attributes.color")
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema = LogicalQuerySchema(
                linkedMapOf(
                    orders to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                    attributes to field(QueryValueType.OBJECT, dynamicChildren = true),
                ),
            ),
            indexes = emptyList(),
            validatorSchema = null,
            model = QueryModel.SNAPSHOT,
            fieldConverter = FieldConverter { it },
        )
        val relative = ElementMatchFilter(
            orders,
            EqualFilter(QueryField("attributes.color"), StringNode.valueOf("red")),
        )
        val absolute = ElementMatchFilter(orders, EqualFilter(color, StringNode.valueOf("red")))

        schema.fields.getValue(attributes).let { fieldSchema ->
            fieldSchema.dynamicChildren.assert().isTrue()
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
            fieldSchema.binding(QueryCapability.EXACT_MATCH)!!.let { binding ->
                binding.resolvedField.assert().isEqualTo(attributes)
                binding.physicalField.assert().isEqualTo(attributes)
            }
        }
        schema.field(color)!!.let { fieldSchema ->
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
            fieldSchema.binding(QueryCapability.EXACT_MATCH)!!.let { binding ->
                binding.resolvedField.assert().isEqualTo(color)
                binding.physicalField.assert().isEqualTo(color)
            }
        }
        schema.resolve(relative).value.assert().isSameAs(relative)
        val resolution = schema.resolve(absolute)
        resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
        val resolved = resolution.value as ElementMatchFilter
        (resolved.predicate as EqualFilter).field.assert().isEqualTo(QueryField("attributes.color"))
    }

    @Test
    fun `self dynamic element should relativize absolute presence predicates`() {
        val items = QueryField("state.items")
        val code = QueryField("state.items.code")
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema = LogicalQuerySchema(
                mapOf(items to field(QueryValueType.OBJECT, QueryCardinality.MANY, dynamicChildren = true)),
            ),
            indexes = emptyList(),
            validatorSchema = null,
            model = QueryModel.SNAPSHOT,
            fieldConverter = FieldConverter { it },
        )
        val relative = ElementMatchFilter(items, ExistsFilter(QueryField("code")))
        val absolute = ElementMatchFilter(items, ExistsFilter(code))

        schema.field(code)!!.rewriteMode.assert().isEqualTo(QueryRewriteMode.NONE)
        schema.resolve(relative).value.assert().isSameAs(relative)
        schema.resolve(absolute).assert().isEqualTo(
            QuerySchemaResolution(
                ElementMatchFilter(items, ExistsFilter(QueryField("code"))),
                QueryCompatibilityLevel.EXACT,
            ),
        )
    }

    @Test
    fun `numeric arrays should retain backend-supported range and aggregation bindings`() {
        val amount = QueryField("state.amounts")
        val logical = LogicalQuerySchema(
            mapOf(
                amount to field(QueryValueType.DECIMAL, cardinality = QueryCardinality.MANY),
            ),
        )
        val schema = MongoQuerySchemaAdapter.bind(logical, emptyList(), null)

        schema.fields.getValue(amount).bindings.keys.assert()
            .contains(QueryCapability.RANGE, QueryCapability.AGGREGATE_NUMERIC)
    }

    @Test
    fun `composed array validators should prove item storage types`() {
        val field = QueryField("state.values")
        val logical = LogicalQuerySchema(
            mapOf(field to field(QueryValueType.INTEGER, cardinality = QueryCardinality.MANY)),
        )
        val array = Document("bsonType", "array")
            .append("items", Document("bsonType", "string"))
        val validators = listOf(
            Document("anyOf", listOf(array, Document("bsonType", "null"))),
            Document("oneOf", listOf(array, Document("bsonType", "null"))),
            Document(
                "allOf",
                listOf(
                    Document("bsonType", listOf("array", "null")),
                    Document("items", Document("bsonType", "string")),
                ),
            ),
        )

        validators.forEach { validator ->
            MongoQuerySchemaAdapter.bind(
                logical,
                emptyList(),
                Document(
                    "properties",
                    Document("state", Document("properties", Document("values", validator))),
                ),
            ).fields.getValue(field).bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
        }
    }

    @Test
    fun `composed validator branches should expose nested property storage types`() {
        listOf("allOf", "anyOf", "oneOf").forEach { composition ->
            val objectSchema = Document("bsonType", "object").append(
                "properties",
                Document("createdAt", Document("bsonType", "string")),
            )
            val branches = if (composition == "allOf") {
                listOf(objectSchema, Document("description", "state"))
            } else {
                listOf(objectSchema, Document("bsonType", "null"))
            }
            val stateSchema = if (composition == "allOf") {
                Document("bsonType", "object").append(composition, branches)
            } else {
                Document(composition, branches)
            }

            MongoQuerySchemaAdapter.bind(
                LogicalQuerySchema(
                    linkedMapOf(
                        QueryField("state") to field(QueryValueType.OBJECT),
                        QueryField("state.createdAt") to field(
                            QueryValueType.INTEGER,
                            semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
                        ),
                    ),
                ),
                emptyList(),
                Document("properties", Document("state", stateSchema)),
            ).let { schema ->
                schema.fields.getValue(QueryField("state")).bindings.keys.assert().contains(
                    QueryCapability.PRESENCE,
                )
                schema.fields.getValue(QueryField("state.createdAt"))
                    .bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
            }
        }
    }

    @Test
    fun `composed array item branches should expose nested property storage types`() {
        listOf("allOf", "anyOf", "oneOf").forEach { composition ->
            val objectSchema = Document("bsonType", "object").append(
                "properties",
                Document("name", Document("bsonType", "int")),
            )
            val branches = if (composition == "allOf") {
                listOf(objectSchema, Document("description", "item"))
            } else {
                listOf(objectSchema, Document("bsonType", "null"))
            }
            val itemSchema = if (composition == "allOf") {
                Document("bsonType", "object").append(composition, branches)
            } else {
                Document(composition, branches)
            }

            MongoQuerySchemaAdapter.bind(
                LogicalQuerySchema(
                    linkedMapOf(
                        QueryField("state.items") to field(
                            QueryValueType.OBJECT,
                            cardinality = QueryCardinality.MANY,
                        ),
                        QueryField("state.items.name") to field(QueryValueType.STRING),
                    ),
                ),
                emptyList(),
                Document(
                    "properties",
                    Document(
                        "state",
                        Document(
                            "properties",
                            Document(
                                "items",
                                Document("bsonType", "array").append("items", itemSchema),
                            ),
                        ),
                    ),
                ),
            ).fields.getValue(QueryField("state.items.name"))
                .bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
        }
    }

    @Test
    fun `alternative object branch without a property should leave its storage type unknown`() {
        listOf("anyOf", "oneOf").forEach { composition ->
            val stateSchema = Document(
                composition,
                listOf(
                    Document("bsonType", "object").append(
                        "properties",
                        Document("amount", Document("bsonType", "string")),
                    ),
                    Document("bsonType", "object").append(
                        "properties",
                        Document("other", Document("bsonType", "string")),
                    ),
                ),
            )

            MongoQuerySchemaAdapter.bind(
                LogicalQuerySchema(
                    mapOf(QueryField("state.amount") to field(QueryValueType.INTEGER)),
                ),
                emptyList(),
                Document("properties", Document("state", stateSchema)),
            ).fields.getValue(QueryField("state.amount")).bindings.keys.assert().contains(
                QueryCapability.RANGE,
                QueryCapability.AGGREGATE_NUMERIC,
            )
        }
    }

    @Test
    fun `typeless field alternative should leave its storage type unknown`() {
        listOf("anyOf", "oneOf").forEach { composition ->
            val validator = Document(
                composition,
                listOf(Document("bsonType", "int"), Document()),
            )

            bindState(Document("amount", validator))
                .fields.getValue(QueryField("state.amount")).bindings.keys.assert().contains(
                    QueryCapability.RANGE,
                    QueryCapability.AGGREGATE_NUMERIC,
                )
        }
    }

    @Test
    fun `opaque logical shapes should expose only presence without a validator`() {
        val field = QueryField("state.opaque")
        val logical = LogicalQuerySchema(
            mapOf(field to field(QueryValueType.STRING).copy(valueTypes = emptySet())),
        )

        MongoQuerySchemaAdapter.bind(logical, emptyList(), null)
            .fields.getValue(field).bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `formatted temporal strings should support relative ranges but not temporal aggregation`() {
        val field = QueryField("state.formatted")
        val logical = LogicalQuerySchema(
            mapOf(
                field to field(
                    QueryValueType.STRING,
                    semanticType = Temporal.Formatted("yyyy-MM-dd"),
                ),
            ),
        )
        val schema = MongoQuerySchemaAdapter.bind(
            logical,
            emptyList(),
            Document(
                "properties",
                Document(
                    "state",
                    Document("bsonType", "object").append(
                        "properties",
                        Document("formatted", Document("bsonType", "string")),
                    ),
                ),
            ),
        )

        schema.fields.getValue(field).bindings.keys.assert()
            .contains(QueryCapability.RANGE)
            .doesNotContain(QueryCapability.AGGREGATE_TEMPORAL)
        schema.resolve(TodayFilter(field)).let { resolved ->
            resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
            (resolved.value as TodayFilter).datePattern.assert().isEqualTo("yyyy-MM-dd")
        }
    }

    @Test
    fun `ordinary strings should support native ranges`() {
        bindState(Document("name", Document("bsonType", "string")))
            .fields.getValue(QueryField("state.name"))
            .bindings.keys.assert().contains(QueryCapability.RANGE)
    }

    @Test
    fun `bind should use conventions validator types and model capabilities`() {
        val validator = Document("bsonType", "object").append(
            "properties",
            Document("_id", Document("bsonType", "string"))
                .append(
                    "state",
                    Document("bsonType", "object").append(
                        "properties",
                        Document("name", Document("bsonType", "string"))
                            .append(
                                "items",
                                Document("bsonType", "array").append(
                                    "items",
                                    Document("bsonType", "object"),
                                ),
                            ),
                    ),
                ),
        )

        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema(),
            listOf(Document("key", Document("all", "text"))),
            validator,
        )

        schema.capabilities.assert().containsExactlyInAnyOrder(
            QueryCapability.FULL_TEXT_TERMS,
            QueryCapability.FULL_TEXT_PHRASE,
        )
        schema.fields.getValue(QueryField("aggregateId"))
            .bindings.getValue(QueryCapability.EXACT_MATCH).let { binding ->
                binding.resolvedField.assert().isEqualTo(QueryField("aggregateId"))
                binding.physicalField.assert().isEqualTo(QueryField("_id"))
                binding.storageType?.value.assert().isEqualTo("string")
            }
        schema.fields.getValue(QueryField("state.name"))
            .bindings.getValue(QueryCapability.LITERAL_MATCH).let { binding ->
                binding.resolvedField.assert().isEqualTo(QueryField("state.name"))
                binding.physicalField.assert().isEqualTo(QueryField("state.name"))
                binding.storageType?.value.assert().isEqualTo("string")
            }
        schema.fields.getValue(QueryField("state.items")).let { fieldSchema ->
            fieldSchema.bindings.getValue(QueryCapability.ELEMENT_SCOPE).let { binding ->
                binding.resolvedField.assert().isEqualTo(QueryField("state.items"))
                binding.physicalField.assert().isEqualTo(QueryField("state.items"))
                binding.storageType?.value.assert().isEqualTo("array")
            }
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
        }
        schema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
        schema.fields.values.flatMap { it.bindings.keys }.assert()
            .doesNotContain(QueryCapability.FULL_TEXT_TERMS)
            .doesNotContain(QueryCapability.FULL_TEXT_PHRASE)
    }

    @Test
    fun `model search should ignore partial and hidden text indexes`() {
        listOf(
            Document("key", Document("all", "text"))
                .append("partialFilterExpression", Document("active", true)),
            Document("key", Document("all", "text")).append("hidden", true),
        ).forEach { index ->
            MongoQuerySchemaAdapter.bind(logicalSchema(), listOf(index), null)
                .capabilities.assert().isEmpty()
        }
    }

    @Test
    fun `bind should leave storage type unknown without a validator`() {
        val schema = MongoQuerySchemaAdapter.bind(logicalSchema(), emptyList(), null)

        schema.fields.values.flatMap { field -> field.bindings.values }
            .forEach { binding -> binding.storageType.assert().isNull() }
        schema.fields.getValue(QueryField("state.amount")).bindings.keys.assert().contains(
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        schema.field(QueryField("tags.department"))?.bindings?.keys.assert().contains(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
        )
        schema.fields.getValue(QueryField("state.nativeDate")).bindings.keys.assert()
            .contains(QueryCapability.PRESENCE, QueryCapability.SORT, QueryCapability.AGGREGATE_TERMS)
            .doesNotContain(QueryCapability.RANGE, QueryCapability.AGGREGATE_TEMPORAL)
    }

    @Test
    fun `bind should keep the only non-null validator union type`() {
        storageType("null", "string")?.value.assert().isEqualTo("string")
    }

    @Test
    fun `bind should leave a real multi-type validator union unknown regardless of order`() {
        storageType("string", "int").assert().isNull()
        storageType("int", "string").assert().isNull()
    }

    @Test
    fun `composed validators should prove nullable native date storage`() {
        val compositions = listOf(
            Document(
                "anyOf",
                listOf(Document("bsonType", "date"), Document("bsonType", "null")),
            ),
            Document(
                "oneOf",
                listOf(Document("bsonType", "date"), Document("bsonType", "null")),
            ),
            Document(
                "allOf",
                listOf(
                    Document("bsonType", listOf("date", "null")),
                    Document("description", "native date"),
                ),
            ),
        )

        compositions.forEach { validator ->
            val bindings = bindState(Document("nativeDate", validator))
                .fields.getValue(QueryField("state.nativeDate"))
                .bindings.keys
            bindings.assert().contains(
                QueryCapability.PRESENCE,
                QueryCapability.SORT,
                QueryCapability.AGGREGATE_TERMS,
                QueryCapability.AGGREGATE_TEMPORAL,
            ).doesNotContain(
                QueryCapability.EXACT_MATCH,
                QueryCapability.LITERAL_MATCH,
                QueryCapability.RANGE,
            )
        }
    }

    @Test
    fun `known nullable numeric unions should retain numeric capabilities while native dates fail closed`() {
        val schema = bindState(
            Document("amount", Document("bsonType", listOf("null", "int", "long", "double", "decimal")))
                .append("createdAt", Document("bsonType", listOf("null", "int", "long")))
                .append("nativeDate", Document("bsonType", listOf("null", "date", "timestamp"))),
        )

        schema.fields.getValue(QueryField("state.amount")).bindings.keys.assert().contains(
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        schema.fields.getValue(QueryField("state.createdAt")).bindings.keys.assert().contains(
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
            QueryCapability.AGGREGATE_TEMPORAL,
        )
        schema.fields.getValue(QueryField("state.nativeDate")).bindings.keys.assert().contains(
            QueryCapability.PRESENCE,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_TEMPORAL,
        )
        schema.fields.getValue(QueryField("state.nativeDate")).bindings.keys.assert().doesNotContain(
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
        )
    }

    @Test
    fun `native BSON date and timestamp should expose only operand-free temporal capabilities`() {
        listOf("date", "timestamp").forEach { bsonType ->
            val bindings = bindState(Document("nativeDate", Document("bsonType", bsonType)))
                .fields.getValue(QueryField("state.nativeDate"))
                .bindings.keys

            bindings.assert().contains(
                QueryCapability.PRESENCE,
                QueryCapability.SORT,
                QueryCapability.AGGREGATE_TERMS,
                QueryCapability.AGGREGATE_TEMPORAL,
            )
            bindings.assert().doesNotContain(
                QueryCapability.EXACT_MATCH,
                QueryCapability.LITERAL_MATCH,
                QueryCapability.RANGE,
            )
        }
    }

    @Test
    fun `mixed logical union on string storage should expose only string capabilities`() {
        val bindings = bindValueTypes(
            setOf(QueryValueType.STRING, QueryValueType.INTEGER),
            listOf("string"),
        ).bindings.keys

        bindings.assert().contains(QueryCapability.PRESENCE, QueryCapability.LITERAL_MATCH)
        bindings.assert().doesNotContain(
            QueryCapability.EXACT_MATCH,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )
    }

    @Test
    fun `mixed logical union on integral storage should expose only numeric capabilities`() {
        val bindings = bindValueTypes(
            setOf(QueryValueType.STRING, QueryValueType.INTEGER),
            listOf("long"),
        ).bindings.keys

        bindings.assert().contains(
            QueryCapability.PRESENCE,
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        bindings.assert().doesNotContain(
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
        )
    }

    @Test
    fun `numeric logical and physical unions should retain numeric capabilities`() {
        bindValueTypes(
            setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL),
            listOf("long", "double"),
        ).bindings.keys.assert().contains(
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )
    }

    @Test
    fun `known string storage should reject integer and native date semantics`() {
        val schema = bindState(
            Document("createdAt", Document("bsonType", "string"))
                .append("nativeDate", Document("bsonType", "string")),
        )

        schema.fields.getValue(QueryField("state.createdAt"))
            .bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
        schema.fields.getValue(QueryField("state.nativeDate"))
            .bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `known numeric storage should reject logical string capabilities`() {
        val schema = bindState(Document("name", Document("bsonType", listOf("int", "long"))))

        schema.fields.getValue(QueryField("state.name"))
            .bindings.keys.assert().containsExactly(QueryCapability.PRESENCE)
        schema.resolve(
            EqualFilter(QueryField("state.name"), StringNode.valueOf("value")),
        ).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `known array item conflict should reject element scope`() {
        bindState(
            Document(
                "items",
                Document("bsonType", "array").append("items", Document("bsonType", "string")),
            ),
        ).fields.getValue(QueryField("state.items"))
            .bindings.assert().isEmpty()
    }

    @Test
    @Suppress("LongMethod")
    fun `known invalid containers should suppress descendants at segment boundaries`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.items") to field(
                    QueryValueType.OBJECT,
                    cardinality = QueryCardinality.MANY,
                ),
                QueryField("state.items.name") to field(QueryValueType.STRING),
                QueryField("state.itemsExtra") to field(QueryValueType.OBJECT),
                QueryField("state.itemsExtra.name") to field(QueryValueType.STRING),
            ),
        )
        val invalid = MongoQuerySchemaAdapter.bind(
            logical,
            emptyList(),
            Document(
                "properties",
                Document(
                    "state",
                    Document("bsonType", "object").append(
                        "properties",
                        Document(
                            "items",
                            Document("bsonType", "array").append("items", Document("bsonType", "string")),
                        ).append(
                            "itemsExtra",
                            Document("bsonType", "object").append(
                                "properties",
                                Document("name", Document("bsonType", "string")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        invalid.fields.getValue(QueryField("state.items.name")).bindings.assert().isEmpty()
        invalid.fields.getValue(QueryField("state.itemsExtra.name")).bindings.keys.assert().contains(
            QueryCapability.EXACT_MATCH,
        )

        listOf(
            Document("properties", Document("name", Document("bsonType", "string"))),
            Document("bsonType", "object").append(
                "properties",
                Document("name", Document("bsonType", "string")),
            ),
        ).forEach { itemSchema ->
            val schema = MongoQuerySchemaAdapter.bind(
                logical,
                emptyList(),
                Document(
                    "properties",
                    Document(
                        "state",
                        Document("bsonType", "object").append(
                            "properties",
                            Document(
                                "items",
                                Document("bsonType", "array").append("items", itemSchema),
                            ),
                        ),
                    ),
                ),
            )

            schema.fields.getValue(QueryField("state.items.name")).bindings.keys.assert().contains(
                QueryCapability.EXACT_MATCH,
            )
        }
    }

    @Test
    fun `dynamic object root should expose exact and presence bindings to descendants`() {
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema(),
            emptyList(),
            Document("properties", Document("tags", Document("bsonType", "object"))),
        )

        schema.fields.getValue(QueryField("tags")).bindings.keys.assert().containsExactlyInAnyOrder(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
        )
        schema.fields.getValue(QueryField("tags")).dynamicChildren.assert().isTrue()
        schema.field(QueryField("tags.department"))?.let { fieldSchema ->
            fieldSchema.binding(QueryCapability.EXACT_MATCH)!!.let { binding ->
                binding.resolvedField.assert().isEqualTo(QueryField("tags.department"))
                binding.physicalField.assert().isEqualTo(QueryField("tags.department"))
            }
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.NONE)
        }
    }

    @Test
    fun `known scalar dynamic root should expose no inheritable bindings`() {
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema(),
            emptyList(),
            Document("properties", Document("tags", Document("bsonType", "string"))),
        )

        schema.fields.getValue(QueryField("tags")).bindings.assert().isEmpty()
        schema.fields.getValue(QueryField("tags")).dynamicChildren.assert().isFalse()
        schema.field(QueryField("tags.department")).assert().isNull()
    }

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
                schema.fields.getValue(QueryField("aggregateId"))
                    .bindings.getValue(QueryCapability.PRESENCE)
                    .storageType?.value.assert().isEqualTo("objectId")
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

    @Test
    fun `Mongo model text index should make a field limited search compatible`() {
        val schema = MongoQuerySchemaAdapter.bind(
            logicalSchema(),
            listOf(Document("key", Document("all", "text"))),
            null,
        )

        val resolution = schema.resolve(
            SearchFilter("hello", setOf(QueryField("state.name"))),
        )

        resolution.compatibility.assert().isEqualTo(QueryCompatibilityLevel.COMPATIBLE)
        (resolution.value as SearchFilter).fields.assert().isEmpty()
    }

    @Test
    fun `service refresh should reread Mongo indexes`() {
        val reads = AtomicInteger()
        val fixture = serviceFixture(
            indexes = {
                if (reads.getAndIncrement() == 0) indexes() else indexes(Document("key", Document("all", "text")))
            },
        )
        val provider = MongoSnapshotQueryBackendFactory(database = fixture.database)
            .create(MOCK_AGGREGATE_METADATA)
            .schemaProvider

        provider.schema().test()
            .assertNext { schema -> schema.capabilities.assert().isEmpty() }
            .verifyComplete()
        provider.refresh().test()
            .assertNext { schema ->
                schema.capabilities.assert().contains(QueryCapability.FULL_TEXT_TERMS)
            }
            .verifyComplete()
        reads.get().assert().isEqualTo(2)
    }

    private fun serviceFixture(indexes: ListIndexesPublisher<Document>): ServiceFixture = serviceFixture { indexes }

    private fun serviceFixture(indexes: () -> ListIndexesPublisher<Document>): ServiceFixture {
        val collection = mockk<MongoCollection<Document>>()
        val database = mockk<MongoDatabase>()
        val findPublisher = mockk<FindPublisher<Document>>()
        val filters = mutableListOf<Bson>()
        val projections = mutableListOf<Bson>()
        val sorts = mutableListOf<Bson>()
        every { database.getCollection(any<String>()) } returns collection
        every { database.listCollections() } returns collections()
        every { collection.namespace } returns MongoNamespace("wow", "snapshots")
        every { collection.listIndexes() } answers { indexes() }
        every { collection.find(capture(filters)) } returns findPublisher
        every { findPublisher.projection(capture(projections)) } returns findPublisher
        every { findPublisher.sort(capture(sorts)) } returns findPublisher
        every { findPublisher.limit(any()) } returns findPublisher
        every { findPublisher.subscribe(any()) } answers {
            Flux.empty<Document>().subscribe(firstArg<Subscriber<in Document>>())
        }
        return ServiceFixture(database, collection, filters, projections, sorts)
    }

    private data class ServiceFixture(
        val database: MongoDatabase,
        val collection: MongoCollection<Document>,
        val filter: List<Bson>,
        val projection: List<Bson>,
        val sort: List<Bson>,
    )

    private fun logicalSchema() = LogicalQuerySchema(
        linkedMapOf(
            QueryField("aggregateId") to field(QueryValueType.STRING),
            QueryField("state.name") to field(QueryValueType.STRING),
            QueryField("state.amount") to field(QueryValueType.DECIMAL),
            QueryField("state.createdAt") to field(
                QueryValueType.INTEGER,
                semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
            ),
            QueryField("state.nativeDate") to field(
                QueryValueType.STRING,
                semanticType = Temporal.Date,
            ),
            QueryField("state.items") to field(
                QueryValueType.OBJECT,
                cardinality = QueryCardinality.MANY,
            ),
            QueryField("tags") to field(
                QueryValueType.OBJECT,
                dynamicChildren = true,
            ),
        ),
    )

    private fun bindState(properties: Document) = MongoQuerySchemaAdapter.bind(
        logicalSchema(),
        emptyList(),
        Document(
            "properties",
            Document("state", Document("bsonType", "object").append("properties", properties)),
        ),
    )

    private fun bindValueTypes(
        valueTypes: Set<QueryValueType>,
        bsonTypes: List<String>,
    ) = MongoQuerySchemaAdapter.bind(
        LogicalQuerySchema(
            mapOf(
                QueryField("state.value") to field(QueryValueType.STRING).copy(valueTypes = valueTypes),
            ),
        ),
        emptyList(),
        Document(
            "properties",
            Document(
                "state",
                Document(
                    "bsonType",
                    "object",
                ).append(
                    "properties",
                    Document("value", Document("bsonType", bsonTypes)),
                ),
            ),
        ),
    ).fields.getValue(QueryField("state.value"))

    private fun storageType(vararg bsonTypes: String) = MongoQuerySchemaAdapter.bind(
        logicalSchema(),
        emptyList(),
        Document(
            "properties",
            Document(
                "state",
                Document("properties", Document("name", Document("bsonType", bsonTypes.toList()))),
            ),
        ),
    ).fields.getValue(QueryField("state.name"))
        .bindings.getValue(QueryCapability.PRESENCE)
        .storageType

    private fun field(
        valueType: QueryValueType,
        cardinality: QueryCardinality = QueryCardinality.SINGLE,
        semanticType: Temporal? = null,
        dynamicChildren: Boolean = false,
        maskRule: MaskRule? = null,
    ) = LogicalQueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = setOf(valueType),
        nullable = false,
        required = true,
        cardinality = cardinality,
        semanticType = semanticType,
        dynamicChildren = dynamicChildren,
        maskRule = maskRule,
    )

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
