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

package me.ahoo.wow.viewstore.starter

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesInput
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesView
import me.ahoo.wow.viewstore.domain.view.ViewState
import reactor.core.scheduler.Schedulers

/**
 * The view store's own routes in OpenAPI, beside the ones Wow renders from the aggregates.
 *
 * [schemaNamePrefix] is the host's own schema name prefix (its context alias, as [me.ahoo.wow.openapi.RouterSpecs]
 * uses), so a type the host's document already has (`example.StringStringMap`) keeps its one name instead of gaining
 * an unprefixed twin at the root.
 *
 * Their schemas are generated once, by [render] at startup (or by the first [merge]), as Wow renders its own routes
 * once ([me.ahoo.wow.openapi.RouterSpecs.buildDocumentation]): generating them may block, so it must not run on a
 * request thread. Every [merge] builds fresh path items and operations around those schemas; the
 * [io.swagger.v3.oas.models.media.Schema] instances are shared, as Wow shares its own, so copy one before changing it.
 *
 * [newComponentContext] builds the context the schemas are generated with, from the schema name prefix.
 */
internal class ViewStoreOpenApi(
    private val paths: ViewStorePaths,
    private val schemaNamePrefix: String,
    private val newComponentContext: (String) -> OpenAPIComponentContext = {
        OpenAPIComponentContext.default(defaultSchemaNamePrefix = it)
    },
) {
    companion object {
        const val TAG = ViewStoreService.SERVICE_ALIAS
        private const val JSON = "application/json"
    }

    /** The schemas of the view store's routes, generated once, and the component schemas they reference. */
    private class RenderedSchemas(
        val errorInfo: Schema<*>,
        val systemView: Schema<*>,
        val preferencesView: Schema<*>,
        val preferencesInput: Schema<*>,
        val commandResult: Schema<*>,
        val replay: Schema<*>,
        val components: Map<String, Schema<*>>,
    )

    private val rendered: Lazy<RenderedSchemas> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { generateSchemas() }

    private fun generateSchemas(): RenderedSchemas {
        val context = newComponentContext(schemaNamePrefix)
        val errorInfo = context.schema(DefaultErrorInfo::class.java)
        val systemView = context.schema(SystemView::class.java)
        val preferencesView = context.schema(ViewPreferencesView::class.java)
        val commandResult = context.schema(CommandResult::class.java)
        val preferencesInput = context.schema(ViewPreferencesInput::class.java)
        val replay = context.schema(MaterializedSnapshot::class.java, ViewState::class.java)
        context.finish()
        return RenderedSchemas(
            errorInfo = errorInfo,
            systemView = systemView,
            preferencesView = preferencesView,
            preferencesInput = preferencesInput,
            commandResult = commandResult,
            replay = replay,
            components = LinkedHashMap(context.schemas),
        )
    }

    /**
     * Generates the schemas now, unless they are generated already. Call it at startup: generating them may block, so
     * it must not run on a request thread. Later [merge] calls generate no schema and never block.
     */
    fun render(): ViewStoreOpenApi {
        rendered.value
        return this
    }

    /** Takes the routes [ViewStoreRouteGuard] closes out of the document, so it shows only what is served. */
    fun withoutClosedRoutes(openApi: OpenAPI, closedContracts: List<HttpRouteContract>) {
        val documented = openApi.paths ?: return
        closedContracts.forEach { contract ->
            val item = documented[contract.path] ?: return@forEach
            item.operation(PathItem.HttpMethod.valueOf(contract.method), null)
            if (item.readOperations().isEmpty()) {
                documented.remove(contract.path)
            }
        }
    }

    /**
     * Adds the view store's routes, their tag, and the component schemas the document does not have yet. Before
     * [render], the first call generates the schemas, which a non-blocking thread (an event loop) refuses with
     * [IllegalStateException]. It may be called concurrently.
     */
    fun merge(openApi: OpenAPI) {
        check(rendered.isInitialized() || !Schedulers.isInNonBlockingThread()) {
            "The view store's OpenAPI schemas are not generated yet, and generating them may block, which " +
                "${Thread.currentThread().name} does not allow: call ViewStoreOpenApi.render() at startup."
        }
        val schemas = rendered.value
        val operations = linkedMapOf(
            paths.systemViews to PathItem().get(systemViews(schemas)),
            paths.systemView to PathItem().get(systemView(schemas)),
            paths.preferences to PathItem()
                .get(getPreferences(schemas))
                .put(setPreferences(schemas)),
            paths.replay to PathItem().get(replay(schemas)),
            paths.claim to PathItem().put(claim(schemas)),
        )
        if (openApi.paths == null) {
            openApi.paths = Paths()
        }
        operations.forEach { (path, item) -> openApi.paths.addPathItem(path, item) }
        if (openApi.components == null) {
            openApi.components = Components()
        }
        schemas.components.forEach { (name, schema) ->
            if (openApi.components.schemas?.containsKey(name) != true) {
                openApi.components.addSchemas(name, schema)
            }
        }
        if (openApi.tags?.none { it.name == TAG } != false) {
            openApi.addTagsItem(Tag().name(TAG).description("The view store's own routes."))
        }
    }

    private fun systemViews(schemas: RenderedSchemas): Operation = operation(
        "view-store.systemViews",
        "The system views, under the shared owner only: the configured ones of the tenant and the views stored " +
            "under tenant (platform) and owner (system), global; a stored view wins over a configured one with its id",
        schemas.errorInfo,
        ok(ArraySchema().items(schemas.systemView)),
    ).addParametersItem(
        Parameter().name(ViewStorePaths.DEFINITION_ID).`in`("query").required(false).schema(StringSchema())
    )

    private fun systemView(schemas: RenderedSchemas): Operation = operation(
        "view-store.systemView",
        "One of the system views: the stored one, else the configured one",
        schemas.errorInfo,
        ok(schemas.systemView),
    ).addParametersItem(pathParameter(ViewStorePaths.ID))

    private fun getPreferences(schemas: RenderedSchemas): Operation = operation(
        "view-store.getPreferences",
        "The owner's preferences of a definition; version 0 when never written",
        schemas.errorInfo,
        ok(schemas.preferencesView),
    ).addParametersItem(pathParameter(ViewStorePaths.DEFINITION_ID))

    private fun setPreferences(schemas: RenderedSchemas): Operation = operation(
        "view-store.setPreferences",
        "Set the owner's preferences of a definition; expected version 0 is never written",
        schemas.errorInfo,
        ok(schemas.commandResult),
    ).addParametersItem(pathParameter(ViewStorePaths.DEFINITION_ID))
        .addParametersItem(header(CommandHeaders.REQUEST_ID, false))
        .addParametersItem(header(CommandHeaders.AGGREGATE_VERSION, false, IntegerSchema()))
        .addParametersItem(header(CommandHeaders.WAIT_STAGE, false))
        .requestBody(
            RequestBody().required(true).content(
                Content().addMediaType(JSON, MediaType().schema(schemas.preferencesInput))
            )
        )

    private fun claim(schemas: RenderedSchemas): Operation = operation(
        "view-store.claimView",
        "Make a shared view personal to the path's owner (the caller's own path)",
        schemas.errorInfo,
        ok(schemas.commandResult),
    ).addParametersItem(pathParameter(ViewStorePaths.ID))
        .addParametersItem(header(CommandHeaders.REQUEST_ID, false))
        .addParametersItem(header(CommandHeaders.AGGREGATE_VERSION, false, IntegerSchema()))
        .addParametersItem(header(CommandHeaders.WAIT_STAGE, false))

    private fun replay(schemas: RenderedSchemas): Operation = operation(
        "view-store.replay",
        "The view as the write with this request id left it; 204 when that write deleted it",
        schemas.errorInfo,
        ok(schemas.replay),
    ).addParametersItem(pathParameter(ViewStorePaths.REQUEST_ID))
        .also { it.responses.addApiResponse("204", ApiResponse().description("The write deleted the view.")) }

    private fun operation(
        operationId: String,
        summary: String,
        errorInfo: Schema<*>,
        ok: ApiResponse,
    ): Operation {
        val error = ApiResponse().description(
            "Error"
        ).content(Content().addMediaType(JSON, MediaType().schema(errorInfo)))
        return Operation()
            .operationId(operationId)
            .summary(summary)
            .addTagsItem(TAG)
            .addParametersItem(pathParameter(ViewStorePaths.TENANT_ID))
            .addParametersItem(pathParameter(ViewStorePaths.OWNER_ID))
            .addParametersItem(header(ViewStoreService.APP_ID_HEADER, true))
            .responses(ApiResponses().addApiResponse("200", ok).addApiResponse("default", error))
    }

    private fun ok(schema: Schema<*>): ApiResponse =
        ApiResponse().description("OK").content(Content().addMediaType(JSON, MediaType().schema(schema)))

    private fun pathParameter(name: String): Parameter =
        Parameter().name(name).`in`("path").required(true).schema(StringSchema())

    private fun header(name: String, required: Boolean, schema: Schema<*> = StringSchema()): Parameter =
        Parameter().name(name).`in`("header").required(required).schema(schema)
}
