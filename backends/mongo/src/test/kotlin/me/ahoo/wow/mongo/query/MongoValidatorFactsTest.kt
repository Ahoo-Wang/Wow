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

package me.ahoo.wow.mongo.query

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import org.bson.Document
import org.junit.jupiter.api.Test

/** Unions, maps and validator compositions: the 9.x adapter's facts over the shapes a Manifest can declare. */
class MongoValidatorFactsTest {
    private fun scalar(type: QueryValueType) = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(type)))

    private fun array(item: QueryFieldDeclaration) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.ARRAY),
        items = DeclarationValue.Set(item),
    )

    private fun objectOf(vararg properties: Pair<String, QueryFieldDeclaration>) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.OBJECT),
        properties = DeclarationValue.Set(properties.toMap()),
    )

    private fun union(vararg alternatives: QueryFieldDeclaration) =
        QueryFieldDeclaration(alternatives = DeclarationValue.Set(alternatives.toList()))

    private val declaration: QuerySchemaDeclaration = QuerySchemaDeclaration(
        querySchema {
            field("state.labels") { values { types(QueryValueType.STRING) } }
            field("state.matrix") { items { items { types(QueryValueType.INTEGER) } } }
            field("state.code") { types(QueryValueType.STRING) }
        }.fields + mapOf(
            QueryField("state.choice") to union(scalar(QueryValueType.STRING), array(scalar(QueryValueType.STRING))),
            QueryField("state.either") to union(
                objectOf("a" to scalar(QueryValueType.STRING)),
                array(objectOf("a" to scalar(QueryValueType.STRING))),
            ),
            QueryField("state.number") to union(scalar(QueryValueType.INTEGER), scalar(QueryValueType.DECIMAL)),
            QueryField("state.mixedArray") to union(array(scalar(QueryValueType.INTEGER)), array(scalar(QueryValueType.STRING))),
        ),
    )

    private fun schema(validator: Document? = null): QueryModelSchema {
        val context = QuerySchemaContext(MongoQueryTestSchemas.TYPE, QueryModel.SNAPSHOT)
        return runBlocking {
            DefaultQueryModelSchemaProvider(
                context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration)))),
                MongoQueryTestSchemas.adapter(validator = validator),
            ).schema()
        }
    }

    private fun QueryModelSchema.capabilities(path: String): Set<QueryCapability> =
        field(QueryField(path))?.capabilities.orEmpty()

    @Test
    fun `without a validator the declared values decide`() {
        val schema = schema()
        schema.capabilities(
            "state.labels.en"
        ).assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.CURSOR_SORT)
        // An array of arrays has no single item to compare.
        schema.capabilities("state.matrix").assert().doesNotContain(QueryCapability.EXACT_MATCH)
        schema.capabilities("state.choice").assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.SORT)
        schema.capabilities("state.choice").assert().doesNotContain(QueryCapability.CURSOR_SORT)
        schema.capabilities("state.either").assert().contains(QueryCapability.PRESENCE)
        schema.capabilities("state.number").assert().contains(QueryCapability.RANGE, QueryCapability.CURSOR_SORT)
        schema.capabilities("state.mixedArray").assert().contains(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `a validator's compositions, maps and items narrow the facts`() {
        val state = Document("bsonType", "object").append(
            "properties",
            Document(
                "labels",
                Document("bsonType", "object").append("additionalProperties", Document("bsonType", "string"))
            )
                .append("code", Document("anyOf", listOf(Document("bsonType", "string"), Document("bsonType", "null"))))
                .append(
                    "number",
                    Document("oneOf", listOf(Document("bsonType", "int"), Document("bsonType", "decimal")))
                )
                .append(
                    "choice",
                    Document("bsonType", listOf("string", "array")).append("items", Document("bsonType", "string")),
                )
                .append(
                    "either",
                    Document(
                        "anyOf",
                        listOf(
                            Document(
                                "bsonType",
                                "object"
                            ).append("properties", Document("a", Document("bsonType", "string"))),
                            Document("bsonType", "array").append("items", Document("bsonType", "object")),
                        ),
                    ),
                )
                .append(
                    "matrix",
                    Document(
                        "allOf",
                        listOf(Document("bsonType", "array"), Document("items", Document("bsonType", "array")))
                    )
                )
                .append("mixedArray", Document("bsonType", "array").append("items", Document("bsonType", "number")))
                .append("extra", Document("bsonType", "array").append("items", Document("bsonType", "object"))),
        )
        val schema = schema(Document("bsonType", "object").append("properties", Document("state", state)))
        schema.capabilities("state.labels.en").assert().contains(QueryCapability.EXACT_MATCH)
        checkNotNull(schema.field(QueryField("state.labels.en"))).binding(QueryCapability.EXACT_MATCH)?.storageTypes
            .assert().isEqualTo(setOf(QueryStorageType("string")))
        schema.capabilities("state.code").assert().contains(QueryCapability.RANGE)
        schema.capabilities("state.number").assert().contains(QueryCapability.RANGE, QueryCapability.AGGREGATE_NUMERIC)
        schema.capabilities("state.choice").assert().contains(QueryCapability.EXACT_MATCH)
        schema.capabilities("state.matrix").assert().doesNotContain(QueryCapability.EXACT_MATCH)
        // Numbers prove no string items.
        schema.capabilities("state.mixedArray").assert().doesNotContain(QueryCapability.EXACT_MATCH)
        schema.capabilities("state.either").assert().contains(QueryCapability.PRESENCE)
    }

    @Test
    fun `validator schemas merge by conjunction and alternatives by union`() {
        val property = QueryPathTemplate(listOf(QueryPathSegment.Property("a")))
        Document(
            "allOf",
            listOf(
                Document("properties", Document("a", Document("bsonType", listOf("int", "string")))),
                Document("properties", Document("a", Document("bsonType", "int")))
            )
        )
            .storageSchemas()[property]?.types.assert().isEqualTo(setOf(QueryStorageType("int")))
        val alternatives = Document(
            "anyOf",
            listOf(
                Document("properties", Document("a", Document("bsonType", "int"))),
                Document("properties", Document("a", Document("bsonType", "string"))),
            ),
        ).storageSchemas()[property]
        alternatives?.types.assert().isEqualTo(setOf(QueryStorageType("int"), QueryStorageType("string")))
        // An alternative that does not declare the field makes it uncertain.
        Document("oneOf", listOf(Document("properties", Document("a", Document("bsonType", "int"))), Document()))
            .storageSchemas()[property]?.uncertain.assert().isEqualTo(true)
        // "number" is every numeric type; "null" adds none.
        Document(
            "properties",
            Document("a", Document("bsonType", listOf("number", "null")))
        ).storageSchemas()[property]?.types
            .assert().isEqualTo(setOf("int", "long", "double", "decimal").map(::QueryStorageType).toSet())
        val key = QueryPathTemplate(listOf(QueryPathSegment.Property("m"), QueryPathSegment.Key(0)))
        val named = QueryPathTemplate(listOf(QueryPathSegment.Property("m"), QueryPathSegment.Property("x")))
        val maps = Document(
            "properties",
            Document(
                "m",
                Document(
                    "additionalProperties",
                    Document("bsonType", "string")
                ).append("properties", Document("x", Document("bsonType", "int")))
            ),
        ).storageSchemas()
        maps.storageAt(named)?.types.assert().isEqualTo(setOf(QueryStorageType("int")))
        maps.storageAt(QueryPathTemplate(listOf(QueryPathSegment.Property("m"), QueryPathSegment.Property("y"))))?.types
            .assert().isEqualTo(setOf(QueryStorageType("string")))
        maps.storageAt(key)?.types.assert().isEqualTo(setOf(QueryStorageType("string")))
        MongoStorageSchema(null).union(MongoStorageSchema(setOf(QueryStorageType("int")))).uncertain.assert().isTrue()
        MongoStorageSchema(null).intersect(MongoStorageSchema(setOf(QueryStorageType("int")))).types
            .assert().isEqualTo(setOf(QueryStorageType("int")))
    }
}
