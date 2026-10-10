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

package me.ahoo.wow.openapi.render

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyBuilder
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.context.HttpComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_HEADERS_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_PARAMETERS_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_REQUEST_BODIES_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_RESPONSES_REF
import me.ahoo.wow.openapi.context.asHttpComponentContext
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import java.lang.reflect.Type

/**
 * Renders a [RouteCatalog] into an OpenAPI 3.1 document. Contracts only reference schemas and components; the renderer
 * resolves them with [componentContext]: it generates the schema of each [HttpSchema.TypeRef], builds each
 * [HttpComponent] once, and finally merges every component the context holds into the document. Two different
 * component instances of the same kind and key are both built; once the context has finished generating the schemas,
 * the render fails unless they built equal components.
 *
 * A renderer renders one document. Neither it nor [componentContext] is thread-safe, so renders that share a context
 * must not run concurrently. Rendering generates schemas, which may block: `RouterSpecs` renders once and merges
 * copies of that document.
 */
internal class OpenApiRenderer(private val componentContext: OpenAPIComponentContext) {
    /** The components built in this render, and what they built, by `$ref`. */
    private val builtComponents = mutableMapOf<String, Pair<HttpComponent<*>, Any>>()

    /**
     * Components that share a `$ref` with a component already built, with what they built. They are compared with
     * the registered component once the schemas are finished: until then, generated schemas are placeholders.
     */
    private val duplicates = mutableListOf<Pair<HttpComponent<*>, Any>>()

    private val buildContext: HttpComponentContext = componentContext.asHttpComponentContext { it.render() }

    /** Builds a component without registering it, so a duplicate leaves the registered component untouched. */
    private val unregisteredContext: OpenAPIComponentContext = object : OpenAPIComponentContext by componentContext {
        override fun parameter(key: String, builder: Parameter.() -> Unit): Parameter = Parameter().also(builder)

        override fun header(key: String, builder: Header.() -> Unit): Header = Header().also(builder)

        override fun requestBody(key: String, builder: RequestBodyBuilder.() -> Unit): RequestBody =
            RequestBodyBuilder().also(builder).build()

        override fun response(key: String, builder: ApiResponseBuilder.() -> Unit): ApiResponse =
            ApiResponseBuilder().also(builder).build()
    }

    fun render(catalog: RouteCatalog, openAPI: OpenAPI): OpenAPI {
        openAPI
            .openapi(OPENAPI_VERSION_3_1)
            .specVersion(SpecVersion.V31)
        if (openAPI.paths == null) {
            openAPI.paths = Paths()
        }

        catalog.routes.groupBy { it.path }.forEach { (path, routes) ->
            val pathItem = openAPI.paths[path] ?: PathItem()
            val pathMetadata = routes.first()
            pathMetadata.summary.takeIf { it.isNotBlank() }?.let {
                pathItem.summary = it
            }
            pathMetadata.description.takeIf { it.isNotBlank() }?.let {
                pathItem.description = it
            }
            routes.forEach { route ->
                pathItem.addOperation(route.method, route.toOperation())
            }
            openAPI.paths.addPathItem(path, pathItem)
        }

        catalog.routes
            .flatMap { it.tags }
            .distinctBy { it.name }
            .forEach { tag ->
                openAPI.addTagsItem(Tag().name(tag.name).description(tag.description))
            }
        componentContext.finish()
        checkDuplicates()
        mergeComponents(openAPI)
        return openAPI
    }

    private fun mergeComponents(openAPI: OpenAPI) {
        val components = openAPI.components ?: Components().also { openAPI.components = it }
        componentContext.schemas.forEach { (name, schema) ->
            components.addSchemas(name, schema)
        }
        componentContext.parameters.forEach { (name, parameter) ->
            components.addParameters(name, parameter)
        }
        componentContext.headers.forEach { (name, header) ->
            components.addHeaders(name, header)
        }
        componentContext.requestBodies.forEach { (name, requestBody) ->
            components.addRequestBodies(name, requestBody)
        }
        componentContext.responses.forEach { (name, response) ->
            components.addResponses(name, response)
        }
    }

    /**
     * Builds this component unless this render already did, and returns what a reference to it renders as: the built
     * component when the context inlines schemas, otherwise a fresh `$ref`. A different component instance with a
     * key already built is built too, without registering it, and checked by [checkDuplicates].
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> HttpComponent<T>.render(): T {
        val ref = refPrefix() + key
        val built = builtComponents[ref]
        val result = when {
            built == null -> build(componentContext, buildContext).also { builtComponents[ref] = this to it }
            built.first === this -> built.second
            else -> {
                if (duplicates.none { it.first === this }) {
                    duplicates.add(this to build(unregisteredContext, buildContext))
                }
                built.second
            }
        } as T
        if (componentContext.inline) {
            return result
        }
        return reference(ref) as T
    }

    /**
     * Rejects a component that shares its kind and key with the registered one but built something different. Runs
     * after the schemas are finished, so components that differ only in a generated schema are told apart.
     */
    private fun checkDuplicates() {
        duplicates.forEach { (component, built) ->
            val registered = if (componentContext.inline) {
                builtComponents.getValue(component.refPrefix() + component.key).second
            } else {
                component.registered().getValue(component.key)
            }
            check(built == registered) {
                "Two different components share the key [${component.refPrefix() + component.key}]: component keys must be unique."
            }
        }
    }

    /** The `$ref` prefix of this component's section. */
    private fun HttpComponent<*>.refPrefix(): String = when (kind) {
        HttpComponent.Kind.PARAMETER -> COMPONENTS_PARAMETERS_REF
        HttpComponent.Kind.HEADER -> COMPONENTS_HEADERS_REF
        HttpComponent.Kind.REQUEST_BODY -> COMPONENTS_REQUEST_BODIES_REF
        HttpComponent.Kind.RESPONSE -> COMPONENTS_RESPONSES_REF
    }

    /** The registered components of this component's section. */
    private fun HttpComponent<*>.registered(): Map<String, Any> = when (kind) {
        HttpComponent.Kind.PARAMETER -> componentContext.parameters
        HttpComponent.Kind.HEADER -> componentContext.headers
        HttpComponent.Kind.REQUEST_BODY -> componentContext.requestBodies
        HttpComponent.Kind.RESPONSE -> componentContext.responses
    }

    private fun HttpComponent<*>.reference(ref: String): Any = when (kind) {
        HttpComponent.Kind.PARAMETER -> Parameter().`$ref`(ref)
        HttpComponent.Kind.HEADER -> Header().`$ref`(ref)
        HttpComponent.Kind.REQUEST_BODY -> RequestBody().`$ref`(ref)
        HttpComponent.Kind.RESPONSE -> ApiResponse().`$ref`(ref)
    }

    private fun HttpRouteContract.toOperation() = Operation().also { operation ->
        operation.operationId = routeId
        operation.summary = summary
        operation.description = description
        operation.tags = tags.map { it.name }
        operation.parameters = parameters.map { it.toParameter() }
        operation.requestBody = requestBody?.toRequestBody()
        operation.responses = responses.toApiResponses()
    }

    private fun HttpParameter.toParameter(): Parameter {
        component?.let { component ->
            return component.render()
        }
        return Parameter()
            .name(name)
            .`in`(location.toParameterIn())
            .required(required)
            .description(description)
            .example(example)
            .schema(schema.toSchema())
    }

    private fun HttpParameterLocation.toParameterIn(): String {
        return when (this) {
            HttpParameterLocation.PATH -> "path"
            HttpParameterLocation.QUERY -> "query"
            HttpParameterLocation.HEADER -> "header"
        }
    }

    private fun HttpRequestBody.toRequestBody(): RequestBody {
        component?.let { component ->
            return component.render()
        }
        return RequestBody()
            .description(description)
            .also {
                if (required) {
                    it.required(required)
                }
                if (contentDeclared) {
                    it.content(content.toContent())
                }
            }
    }

    private fun List<HttpResponse>.toApiResponses(): ApiResponses {
        return ApiResponses().also { apiResponses ->
            forEach { response ->
                apiResponses.addApiResponse(response.statusCode, response.toApiResponse())
            }
        }
    }

    private fun HttpResponse.toApiResponse(): ApiResponse {
        component?.let { component ->
            return component.render()
        }
        return ApiResponse()
            .description(description)
            .also {
                if (headers.isNotEmpty()) {
                    it.headers(headers.toHeaders())
                }
                if (contentDeclared) {
                    it.content(content.toContent())
                }
            }
    }

    private fun List<HttpHeader>.toHeaders(): Map<String, Header> {
        return associate { header ->
            header.name to header.toHeader()
        }
    }

    private fun HttpHeader.toHeader(): Header {
        component?.let { component ->
            return component.render()
        }
        return Header()
            .description(description)
            .schema(schema.toSchema())
    }

    private fun List<HttpContent>.toContent(): Content {
        return Content().also { content ->
            forEach { httpContent ->
                content.addMediaType(
                    httpContent.mediaType,
                    MediaType().schema(httpContent.schema.toSchema())
                )
            }
        }
    }

    private fun HttpSchema.toSchema(): Schema<*> {
        return when (this) {
            HttpSchema.String -> StringSchema()
            HttpSchema.Integer -> IntegerSchema()
            HttpSchema.Object -> ObjectSchema()
            is HttpSchema.TypeRef -> componentContext.schema(type, *typeArguments.resolveAll())
            is HttpSchema.Array -> ArraySchema().items(item.toSchema())
            is HttpSchema.Raw -> schema
        }
    }

    private fun List<HttpSchema.TypeRef>.resolveAll(): Array<Type> = map { it.resolve() }.toTypedArray()

    /** The type a type argument names: its raw type, or the type resolved with its own arguments. */
    private fun HttpSchema.TypeRef.resolve(): Type {
        if (typeArguments.isEmpty()) {
            return type
        }
        return componentContext.resolveType(type, *typeArguments.resolveAll())
    }

    private fun PathItem.addOperation(method: String, operation: Operation) {
        when (method.uppercase()) {
            Https.Method.GET -> get(operation)
            Https.Method.POST -> post(operation)
            Https.Method.PUT -> put(operation)
            Https.Method.DELETE -> delete(operation)
            Https.Method.PATCH -> patch(operation)
            Https.Method.OPTIONS -> options(operation)
            Https.Method.HEAD -> head(operation)
            Https.Method.TRACE -> trace(operation)
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }
    }

    companion object {
        private const val OPENAPI_VERSION_3_1 = "3.1.0"
    }
}
