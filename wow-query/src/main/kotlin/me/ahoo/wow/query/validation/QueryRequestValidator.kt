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

import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.query.schema.QuerySchemaView
import reactor.core.publisher.Mono

class QueryRequestValidator(
    limits: QueryStructureLimits
) {
    private val expressionValidator = QueryExpressionValidator(limits)
    private val schemaValidator = QueryRequestSchemaValidator.create(limits)

    fun <R : QueryRequest> validateStructure(request: R): R {
        expressionValidator.validateStructure(request.expression)
        return request
    }

    fun <R : QueryRequest> validateSchema(request: R, schema: QuerySchemaView): R {
        return schemaValidator.validate(request, request.expression, schema)
    }

    fun <R : QueryRequest> validate(
        request: R,
        schemaResolver: () -> Mono<out QuerySchemaView>
    ): Mono<R> = Mono.defer {
        validateStructure(request)
        schemaResolver().map { schema -> validateSchema(request, schema) }
    }
}
