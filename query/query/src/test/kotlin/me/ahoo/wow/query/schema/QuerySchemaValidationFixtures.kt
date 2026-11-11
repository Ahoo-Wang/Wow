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

@file:Suppress("LongMethod")

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.resolve.QueryResolver
import java.time.Instant

/** What the schema validation tests share: admitting a query of each shape through the trusted path. */
internal object QuerySchemaValidationFixtures {
    /*
     * The validation this test pins runs in admission's single resolution pass; each helper admits through the trusted
     * path and returns its input, so a test reads as the rule it checks.
     */
    fun validateQuery(query: SingleQuery, schema: QueryModelSchema): SingleQuery =
        query.also { QueryAdmission.Trusted.single(it, schema) }

    fun validateQuery(query: ListQuery, schema: QueryModelSchema): ListQuery =
        query.also { QueryAdmission.Trusted.list(it, schema) }

    fun validateQuery(query: PagedQuery, schema: QueryModelSchema): PagedQuery =
        query.also { QueryAdmission.Trusted.paged(it, schema) }

    /** The cursor's own sort: its first field stands in for the tie-breaker admission appends. */
    fun validateQuery(query: CursorQuery, schema: QueryModelSchema): CursorQuery = query.also {
        val tieBreaker = it.sort.firstOrNull()?.field ?: schema.requireIdentityField()
        val size = it.size ?: QueryGateway.DEFAULT_STREAM_CHUNK_SIZE
        QueryResolver(schema, Instant.now()).cursor(it, listOf(tieBreaker), size)
    }

    fun validateQuery(query: FilterExpression, schema: QueryModelSchema): FilterExpression =
        query.also { QueryAdmission.Trusted.count(CountQuery(it), schema) }

    fun validateQuery(query: AggregationQuery, schema: QueryModelSchema): AggregationQuery =
        query.also { QueryAdmission.Trusted.aggregate(it, schema) }
}
