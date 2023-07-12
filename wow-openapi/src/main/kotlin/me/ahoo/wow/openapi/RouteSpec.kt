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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.Wow

interface RouteSpec : Identifier {
    /**
     * @see [io.swagger.v3.oas.models.Operation.getOperationId]
     */
    override val id: String
    val path: String
    val method: String
    val summary: String
    val description: String
        get() = ""
    val tags: List<String>
        get() = listOf(Wow.WOW)
    val schemas: Map<String, Schema<*>>
    val parameters: List<Parameter>
        get() = listOf()
    val requestBody: RequestBody?
    val responses: ApiResponses

    fun build(): RouteSpec
}

fun RouteSpec.toOperation(): Operation {
    val operation = Operation()
    operation.operationId = id
    operation.summary = summary
    operation.description = description
    operation.tags = tags
    operation.requestBody = requestBody
    operation.parameters = parameters
    operation.responses = responses
    return operation
}

fun RouteSpec.toPathItem(): PathItem {
    val pathItem = PathItem()
    pathItem.summary = summary
    pathItem.description = description
    val operation = toOperation()

    when (method) {
        Https.Method.GET -> pathItem.get(operation)
        Https.Method.POST -> pathItem.post(operation)
        Https.Method.PUT -> pathItem.put(operation)
        Https.Method.DELETE -> pathItem.delete(operation)
        Https.Method.OPTIONS -> pathItem.options(operation)
        Https.Method.HEAD -> pathItem.head(operation)
        Https.Method.PATCH -> pathItem.patch(operation)
        Https.Method.TRACE -> pathItem.trace(operation)
        else -> throw IllegalArgumentException("Unsupported method: $method")
    }
    return pathItem
}
