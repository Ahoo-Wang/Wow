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

package me.ahoo.wow.openapi.migration

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.catalog.RouteCatalogBuilder
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_HEADERS_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_PARAMETERS_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_PREFIX
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_REQUEST_BODIES_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_RESPONSES_REF
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.HttpTag

class RouteSpecContractAdapter(private val componentContext: OpenAPIComponentContext) {

    fun toRouteCatalog(routeSpecs: Iterable<RouteSpec>): RouteCatalog {
        val routeSpecList = routeSpecs.toList()
        val pathMetadata = routeSpecList.groupBy { it.path }.mapValues { (_, routes) ->
            routes.first()
        }
        return RouteCatalogBuilder()
            .addAll(
                routeSpecList.map { routeSpec ->
                    toContract(
                        routeSpec,
                        pathMetadata.getValue(routeSpec.path)
                    )
                }
            )
            .build()
    }

    fun toContract(routeSpec: RouteSpec): HttpRouteContract {
        return toContract(routeSpec, routeSpec)
    }

    private fun toContract(routeSpec: RouteSpec, pathRouteSpec: RouteSpec): HttpRouteContract {
        return HttpRouteContract(
            routeId = routeSpec.id,
            method = routeSpec.method,
            path = routeSpec.path,
            handlerKey = routeSpec.id,
            summary = routeSpec.summary,
            description = routeSpec.description,
            pathSummary = pathRouteSpec.summary,
            pathDescription = pathRouteSpec.description,
            accept = routeSpec.accept,
            produce = routeSpec.responses.toHttpResponses()
                .flatMap { response -> response.content.map { it.mediaType } }
                .distinct(),
            parameters = routeSpec.toHttpParameters(),
            requestBody = routeSpec.requestBody.toHttpRequestBody(),
            responses = routeSpec.responses.toHttpResponses(),
            tags = routeSpec.tags.map { tag ->
                HttpTag(name = tag.name, description = tag.description)
            }
        )
    }

    private fun RouteSpec.toHttpParameters(): List<HttpParameter> {
        val parameters = parameters.mapNotNull { parameter ->
            val componentRef = parameter.componentKey(COMPONENTS_PARAMETERS_REF)
            parameter.resolveParameter().toHttpParameter(componentRef)
        }.distinctBy {
            it.location to it.name
        }.toMutableList()

        val templateVariables = pathTemplateVariables()
        val pathParameterNames = parameters
            .filter { it.location == HttpParameterLocation.PATH }
            .map { it.name }
            .toSet()
        (templateVariables - pathParameterNames).forEach { variable ->
            parameters.add(
                HttpParameter(
                    name = variable,
                    location = HttpParameterLocation.PATH,
                    required = true,
                    schema = HttpSchema.String
                )
            )
        }
        return parameters.filterNot {
            it.location == HttpParameterLocation.PATH && it.name !in templateVariables
        }
    }

    private fun RouteSpec.pathTemplateVariables(): Set<String> {
        return PATH_VARIABLE_REGEX.findAll(path)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun Parameter?.resolveParameter(): Parameter? {
        if (this == null) {
            return null
        }
        return componentKey(`$ref`, COMPONENTS_PARAMETERS_REF)
            ?.let { componentContext.parameters[it] }
            ?: this
    }

    private fun RequestBody?.resolveRequestBody(): RequestBody? {
        if (this == null) {
            return null
        }
        return componentKey(`$ref`, COMPONENTS_REQUEST_BODIES_REF)
            ?.let { componentContext.requestBodies[it] }
            ?: this
    }

    private fun ApiResponse?.resolveResponse(): ApiResponse? {
        if (this == null) {
            return null
        }
        return componentKey(`$ref`, COMPONENTS_RESPONSES_REF)
            ?.let { componentContext.responses[it] }
            ?: this
    }

    private fun Header?.resolveHeader(): Header? {
        if (this == null) {
            return null
        }
        return componentKey(`$ref`, COMPONENTS_HEADERS_REF)
            ?.let { componentContext.headers[it] }
            ?: this
    }

    private fun Parameter?.toHttpParameter(componentRef: String? = null): HttpParameter? {
        if (this == null) {
            return null
        }
        val parameterName = name?.takeIf { it.isNotBlank() } ?: return null
        val location = `in`?.toHttpParameterLocation() ?: return null
        return HttpParameter(
            name = parameterName,
            location = location,
            required = required == true || location == HttpParameterLocation.PATH,
            schema = schema.toHttpSchema(),
            description = description,
            example = example ?: schema?.example,
            componentRef = componentRef
        )
    }

    private fun RequestBody?.toHttpRequestBody(): HttpRequestBody? {
        val componentRef = componentKey(this?.`$ref`, COMPONENTS_REQUEST_BODIES_REF)
        val requestBody = resolveRequestBody() ?: return null
        val content = requestBody.content.toHttpContent()
        return HttpRequestBody(
            required = requestBody.required == true,
            description = requestBody.description,
            content = content,
            contentDeclared = requestBody.content != null,
            componentRef = componentRef
        )
    }

    private fun Map<String, ApiResponse>.toHttpResponses(): List<HttpResponse> {
        return map { (statusCode, response) ->
            val componentRef = componentKey(response.`$ref`, COMPONENTS_RESPONSES_REF)
            val resolvedResponse = response.resolveResponse()
            val content = resolvedResponse?.content.toHttpContent()
            HttpResponse(
                statusCode = statusCode,
                description = resolvedResponse?.description,
                headers = resolvedResponse?.headers.toHttpHeaders(),
                content = content,
                contentDeclared = resolvedResponse?.content != null,
                componentRef = componentRef
            )
        }
    }

    private fun Map<String, Header>?.toHttpHeaders(): List<HttpHeader> {
        return this?.map { (name, header) ->
            val componentRef = componentKey(header.`$ref`, COMPONENTS_HEADERS_REF)
            val resolvedHeader = header.resolveHeader()
            HttpHeader(
                name = name,
                schema = resolvedHeader?.schema.toHttpSchema(),
                description = resolvedHeader?.description,
                componentRef = componentRef
            )
        } ?: emptyList()
    }

    private fun Content?.toHttpContent(): List<HttpContent> {
        return this?.map { (mediaTypeName, mediaType) ->
            HttpContent(
                mediaType = mediaTypeName,
                schema = mediaType.schema.toHttpSchema()
            )
        } ?: emptyList()
    }

    private fun String.toHttpParameterLocation(): HttpParameterLocation? {
        return when (lowercase()) {
            ParameterIn.PATH.toString() -> HttpParameterLocation.PATH
            ParameterIn.QUERY.toString() -> HttpParameterLocation.QUERY
            ParameterIn.HEADER.toString() -> HttpParameterLocation.HEADER
            else -> null
        }
    }

    private fun Schema<*>?.toHttpSchema(): HttpSchema {
        if (this == null) {
            return HttpSchema.Unspecified
        }
        componentKey(`$ref`, COMPONENTS_SCHEMAS_REF)?.let {
            return HttpSchema.ComponentRef(it)
        }
        if (this is ArraySchema) {
            return HttpSchema.Array(items.toHttpSchema())
        }
        format?.takeIf { type.isNullOrBlank() }?.let {
            return HttpSchema.Formatted(it)
        }
        return when (type) {
            "string" -> HttpSchema.String
            "boolean" -> HttpSchema.Boolean
            "integer" -> {
                if (format == "int64") {
                    HttpSchema.Long
                } else {
                    HttpSchema.Integer
                }
            }
            "object" -> HttpSchema.Object
            else -> HttpSchema.Unspecified
        }
    }

    private fun componentKey(ref: String?, prefix: String): String? {
        return ref?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotBlank() }
    }

    private fun Parameter.componentKey(prefix: String): String? {
        return componentKey(`$ref`, prefix)
    }

    companion object {
        private val PATH_VARIABLE_REGEX = Regex("\\{([^}]+)}")
        private const val COMPONENTS_SCHEMAS_REF = "${COMPONENTS_PREFIX}schemas/"
    }
}
