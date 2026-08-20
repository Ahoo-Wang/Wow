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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryCapabilityId
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QuerySort
import me.ahoo.wow.query.policy.QueryOperation
import me.ahoo.wow.query.policy.QueryResultKind
import me.ahoo.wow.query.schema.QuerySchema
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

class SecuredQuery private constructor(
    val target: NamedAggregate,
    val operation: QueryOperation,
    val resultKind: QueryResultKind,
    val filter: QueryExpression,
    val sort: List<QuerySort>,
    val offset: Long,
    val limit: Int?,
    val resultFields: Set<LogicalField>,
    internal val projection: QueryProjection,
    val recordFields: Set<LogicalField>,
    val capabilities: Set<QueryCapabilityId>,
    val budget: QueryBudget,
    val deadline: Instant?,
    val schema: QuerySchema
) {
    override fun toString(): String =
        "SecuredQuery(operation=$operation, resultKind=$resultKind, target=<redacted>, filter=<redacted>)"

    internal companion object {
        fun create(
            target: NamedAggregate,
            operation: QueryOperation,
            resultKind: QueryResultKind,
            filter: QueryExpression,
            sort: List<QuerySort>,
            offset: Long,
            limit: Int?,
            resultFields: Set<LogicalField>,
            projection: QueryProjection,
            recordFields: Set<LogicalField>,
            capabilities: Set<QueryCapabilityId>,
            budget: QueryBudget,
            deadline: Instant?,
            schema: QuerySchema
        ): SecuredQuery = SecuredQuery(
            target,
            operation,
            resultKind,
            filter,
            sort.toList(),
            offset,
            limit,
            resultFields.toSet(),
            projection,
            recordFields.toSet(),
            capabilities.toSet(),
            budget,
            deadline,
            schema
        )
    }
}

fun interface QueryRouter {
    fun route(query: SecuredQuery): QueryBackend
}

interface QueryBackend {
    val id: String

    fun validate(query: SecuredQuery)

    fun stream(query: SecuredQuery): Flux<ObjectNode>

    fun page(query: SecuredQuery): Mono<QueryPage<ObjectNode>>

    fun count(query: SecuredQuery): Mono<Long>
}
