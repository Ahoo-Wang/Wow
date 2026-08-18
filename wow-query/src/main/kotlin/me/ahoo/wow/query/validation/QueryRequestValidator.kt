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

package me.ahoo.wow.query.validation

import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.schema.QuerySchemaView
import reactor.core.publisher.Mono

class QueryRequestValidator(
    limits: QueryStructureLimits
) {
    private val expressionValidator = QueryExpressionValidator(limits)

    fun <R : QueryRequest> validateStructure(request: R): R {
        expressionValidator.validateStructure(request.expression)
        return request
    }

    fun <R : QueryRequest> validateSchema(request: R, schema: QuerySchemaView): R {
        if (schema.target != request.target) invalidQuery()
        expressionValidator.validateSchema(request.expression, schema)
        when (request) {
            is SingleQueryRequest<*> -> validateResultRequest(request.resultShape, request.sort, schema)
            is ListQueryRequest<*> -> validateResultRequest(request.resultShape, request.sort, schema)
            is PageQueryRequest<*> -> validateResultRequest(request.resultShape, request.sort, schema)
            is CountQueryRequest -> Unit
        }
        return request
    }

    fun <R : QueryRequest> validate(
        request: R,
        schemaResolver: () -> Mono<out QuerySchemaView>
    ): Mono<R> = Mono.defer {
        validateStructure(request)
        schemaResolver().map { schema -> validateSchema(request, schema) }
    }

    private fun validateResultRequest(
        resultShape: QueryResultShape<*>,
        sort: List<me.ahoo.wow.api.query.gateway.QuerySort>,
        schema: QuerySchemaView
    ) {
        val projection = when (resultShape) {
            QueryResultShape.Dynamic -> null
            is QueryResultShape.ProjectedDynamic -> resultShape.projection
            is QueryResultShape.Typed<*> -> resultShape.projection
        }
        val projectionFields = when (projection) {
            is QueryProjection.Include -> projection.fields
            is QueryProjection.Exclude -> projection.fields
            QueryProjection.All,
            null -> emptySet()
        }
        projectionFields.forEach { field ->
            if (schema.field(field)?.projectable != true) invalidQuery()
        }
        sort.forEach { fieldSort ->
            if (schema.field(fieldSort.field)?.sortable != true) invalidQuery()
        }
    }
}
