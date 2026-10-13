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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.querySchema

/** The order model of the unit tests, compiled over [PostgresSnapshotStorageAdapter]. */
object QueryTestSchemas {
    val TYPE = AggregateType("shop", "order")
    const val TABLE = "\"wow_snapshot_shop\""

    val SCHEMA: QueryModelSchema by lazy {
        val context = QuerySchemaContext(TYPE, QueryModel.SNAPSHOT)
        val declaration = querySchema {
            field("state.name") { types(QueryValueType.STRING) }
            field("state.amount") { types(QueryValueType.DECIMAL) }
            field("state.quantity") { types(QueryValueType.INTEGER) }
            field("state.flag") { types(QueryValueType.BOOLEAN) }
            field("state.createdAt") {
                types(QueryValueType.INTEGER)
                temporalEpoch()
            }
            field("state.issuedAt") {
                types(QueryValueType.STRING)
                semantic(Temporal.Date)
            }
            field("state.code") {
                types(QueryValueType.STRING)
                temporalFormatted("yyyy-MM-dd")
            }
            field("state.mixed") { types(QueryValueType.STRING, QueryValueType.INTEGER) }
            field("state.keywords") { items { types(QueryValueType.STRING) } }
            field("state.lines") {
                items {
                    kind(QueryValueKind.OBJECT)
                    property("sku") { types(QueryValueType.STRING) }
                }
            }
        }
        runBlocking {
            DefaultQueryModelSchemaProvider(
                context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration)))),
                PostgresSnapshotStorageAdapter,
            ).schema()
        }
    }
}
