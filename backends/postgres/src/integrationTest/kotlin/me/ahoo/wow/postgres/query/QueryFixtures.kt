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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import java.time.Instant

/** The order model the query integration tests share, beyond the TCK's. */
object QueryFixtures {
    /** 2026-01-05T00:00:00Z, a Monday. */
    val T0: Long = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
    const val DAY: Long = 86_400_000L
    const val HOUR: Long = 3_600_000L
    const val TENANT = "tenant"

    val DECLARATION: QuerySchemaDeclaration = querySchema {
        field("state.name") { types(QueryValueType.STRING) }
        field("state.status") { types(QueryValueType.STRING) }
        field("state.amount") { types(QueryValueType.DECIMAL) }
        field("state.quantity") { types(QueryValueType.INTEGER) }
        field("state.flag") { types(QueryValueType.BOOLEAN) }
        field("state.note") { types(QueryValueType.STRING) }
        field("state.createdAt") {
            types(QueryValueType.INTEGER)
            temporalEpoch()
        }
        field("state.createdSeconds") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.SECONDS)
        }
        field("state.createdMicros") {
            types(QueryValueType.INTEGER)
            temporalEpoch(java.util.concurrent.TimeUnit.MICROSECONDS)
        }
        field("state.code") {
            types(QueryValueType.STRING)
            temporalFormatted("yyyy-MM-dd")
        }
        field("state.issuedAt") {
            types(QueryValueType.STRING)
            semantic(me.ahoo.wow.query.schema.Temporal.Date)
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

    suspend fun schema(type: AggregateType, adapter: QueryStorageAdapter): QueryModelSchema {
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        return DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
            adapter,
        ).schema()
    }

    @Suppress("LongParameterList")
    fun snapshot(
        type: AggregateType,
        id: String,
        state: String,
        tenantId: String = TENANT,
        scope: Scope = Scope("owner-1"),
        deleted: Boolean = false,
        version: Int = 1,
        lastEventTime: Long = T0,
        tags: Map<String, List<String>> = emptyMap(),
    ) = SnapshotRecord(
        ref = type.ref(tenantId, id),
        version = version,
        schemaFingerprint = "it",
        state = state,
        scope = scope,
        deleted = deleted,
        firstEventTime = T0 - DAY,
        lastEventTime = lastEventTime,
        tags = tags,
    )

    /** Five orders whose values cover nulls, missing fields, unicode, wildcards and arrays. */
    fun orders(type: AggregateType): List<SnapshotRecord> = listOf(
        snapshot(
            type,
            "a",
            """{"name":"Ärger","status":"PAID","amount":10.50,"quantity":1,"flag":true,"note":null,""" +
                """"createdAt":$T0,"createdSeconds":${T0 / 1000},"createdMicros":${T0 * 1000},"code":"2026-01-05",""" +
                """"keywords":["x","y_1"],"scores":[1,5],"mixed":"one",""" +
                """"lines":[{"sku":"A","price":5,"quantity":1,"parts":[{"name":"bolt"}]}]}""",
            tags = mapOf("region" to listOf("east", "west")),
        ),
        snapshot(
            type,
            "b",
            """{"name":"beta","status":"NEW","amount":20,"quantity":2,"flag":false,""" +
                """"createdAt":${T0 + DAY + 3 * HOUR},"createdSeconds":${(T0 + DAY) / 1000},"code":"2026-01-06",""" +
                """"keywords":["100%"],"scores":[7],"mixed":2,"lines":[]}""",
            scope = Scope("owner-2", "space-1"),
            version = 2,
            lastEventTime = T0 + DAY,
            tags = mapOf("region" to listOf("east")),
        ),
        snapshot(
            type,
            "c",
            """{"status":null,"amount":30,"quantity":2,"flag":true,"createdAt":${T0 + 2 * DAY + 20 * HOUR},""" +
                """"keywords":[],"lines":{"sku":"Z","price":1,"quantity":9}}""",
            version = 3,
            lastEventTime = T0 + 2 * DAY,
        ),
        snapshot(
            type,
            "d",
            """{"name":"Delta","status":"NEW","amount":40,"quantity":4,"keywords":"z","scores":[2,9],""" +
                """"lines":[{"sku":"B","price":10,"quantity":2},{"sku":"C","price":1,"quantity":3,""" +
                """"parts":[{"name":"nut"},{"name":"bolt"}]}]}""",
            scope = Scope.NONE,
            version = 4,
            lastEventTime = T0 + 9 * DAY,
        ),
        snapshot(
            type,
            "e",
            """{"name":"epsilon","status":"CANCELLED","amount":"n/a","quantity":3,"createdAt":${T0 - 40 * DAY}}""",
            deleted = true,
            version = 5,
            lastEventTime = T0 + 3 * DAY,
        ),
    )
}
