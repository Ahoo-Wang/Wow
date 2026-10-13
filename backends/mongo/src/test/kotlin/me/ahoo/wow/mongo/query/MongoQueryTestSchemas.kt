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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.querySchema
import org.bson.Document

/** The order model of the unit tests, compiled over the facts [MongoQuerySchemaAdapter] derives without a database. */
object MongoQueryTestSchemas {
    val TYPE = AggregateType("shop", "order")

    val DECLARATION: QuerySchemaDeclaration = querySchema {
        field("state.name") { types(QueryValueType.STRING) }
        field("state.status") { types(QueryValueType.STRING) }
        field("state.amount") { types(QueryValueType.DECIMAL) }
        field("state.quantity") { types(QueryValueType.INTEGER) }
        field("state.flag") { types(QueryValueType.BOOLEAN) }
        field("state.createdAt") {
            types(QueryValueType.INTEGER)
            temporalEpoch()
        }
        field("state.createdSeconds") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.SECONDS)
        }
        field("state.createdNanos") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.NANOSECONDS)
        }
        field("state.createdMinutes") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.MINUTES)
        }
        field("state.createdHours") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.HOURS)
        }
        field("state.createdDays") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.DAYS)
        }
        field("state.createdMicros") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.MICROSECONDS)
        }
        field("state.issuedAt") {
            types(QueryValueType.STRING)
            semantic(Temporal.Date)
        }
        field("state.mixed") { types(QueryValueType.STRING, QueryValueType.INTEGER) }
        field("state.keywords") { items { types(QueryValueType.STRING) } }
        field("state.scores") { items { types(QueryValueType.INTEGER) } }
        field("state.lines") {
            items {
                kind(QueryValueKind.OBJECT)
                property("sku") { types(QueryValueType.STRING) }
                property("price") { types(QueryValueType.DECIMAL) }
                property("quantity") { types(QueryValueType.INTEGER) }
                property("parts") {
                    items {
                        kind(QueryValueKind.OBJECT)
                        property("name") { types(QueryValueType.STRING) }
                    }
                }
            }
        }
    }

    /** The adapter's facts over [indexes] and a collection [validator], without reading a collection. */
    fun adapter(indexes: List<Document> = emptyList(), validator: Document? = null): QueryStorageAdapter =
        object : QueryStorageAdapter {
            override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
                MongoQuerySchemaAdapter.facts(logicalSchema, indexes, validator)
        }

    fun schema(adapter: QueryStorageAdapter = adapter()): QueryModelSchema {
        val context = QuerySchemaContext(TYPE, QueryModel.SNAPSHOT)
        return runBlocking {
            DefaultQueryModelSchemaProvider(
                context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
                adapter,
            ).schema()
        }
    }

    val SCHEMA: QueryModelSchema by lazy { schema() }
}
