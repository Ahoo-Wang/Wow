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
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesInput
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesView
import me.ahoo.wow.viewstore.domain.view.ViewState

/**
 * The view store's own routes in OpenAPI, beside the ones Wow renders from the aggregates.
 *
 * [schemaNamePrefix] is the host's own schema name prefix (its context alias, as [me.ahoo.wow.openapi.RouterSpecs]
 * uses), so a type the host's document already has (`example.StringStringMap`) keeps its one name instead of gaining
 * an unprefixed twin at the root.
 */
internal class ViewStoreOpenApi(private val paths: ViewStorePaths, private val schemaNamePrefix: String) {
    companion object {
        const val TAG = ViewStoreService.SERVICE_ALIAS
        private const val JSON = "application/json"
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

    fun merge(openApi: OpenAPI) {
        val context = OpenAPIComponentContext.default(defaultSchemaNamePrefix = schemaNamePrefix)
        val errorInfo = context.schema(DefaultErrorInfo::class.java)
        val operations = linkedMapOf(
            paths.systemViews to PathItem().get(systemViews(context, errorInfo)),
            paths.systemView to PathItem().get(systemView(context, errorInfo)),
            paths.preferences to PathItem()
                .get(getPreferences(context, errorInfo))
                .put(setPreferences(context, errorInfo)),
            paths.replay to PathItem().get(replay(context, errorInfo)),
            paths.claim to PathItem().put(claim(context, errorInfo)),
        )
        context.finish()
        if (openApi.paths == null) {
            openApi.paths = Paths()
        }
        operations.forEach { (path, item) -> openApi.paths.addPathItem(path, item) }
        if (openApi.components == null) {
            openApi.components = Components()
        }
        context.schemas.forEach { (name, schema) ->
            if (openApi.components.schemas?.containsKey(name) != true) {
                openApi.components.addSchemas(name, schema)
            }
        }
        if (openApi.tags?.none { it.name == TAG } != false) {
            openApi.addTagsItem(Tag().name(TAG).description("The view store's own routes."))
        }
    }

    private fun systemViews(context: OpenAPIComponentContext, errorInfo: Schema<*>): Operation = operation(
        "view-store.systemViews",
        "The server's system views, under the shared owner only",
        errorInfo,
        ok(ArraySchema().items(context.schema(SystemView::class.java))),
    ).addParametersItem(
        Parameter().name(ViewStorePaths.DEFINITION_ID).`in`("query").required(false).schema(StringSchema())
    )

    private fun systemView(context: OpenAPIComponentContext, errorInfo: Schema<*>): Operation = operation(
        "view-store.systemView",
        "One of the server's system views",
        errorInfo,
        ok(context.schema(SystemView::class.java)),
    ).addParametersItem(pathParameter(ViewStorePaths.ID))

    private fun getPreferences(context: OpenAPIComponentContext, errorInfo: Schema<*>): Operation = operation(
        "view-store.getPreferences",
        "The owner's preferences of a definition; version 0 when never written",
        errorInfo,
        ok(context.schema(ViewPreferencesView::class.java)),
    ).addParametersItem(pathParameter(ViewStorePaths.DEFINITION_ID))

    private fun setPreferences(context: OpenAPIComponentContext, errorInfo: Schema<*>): Operation = operation(
        "view-store.setPreferences",
        "Set the owner's preferences of a definition; expected version 0 is never written",
        errorInfo,
        ok(context.schema(CommandResult::class.java)),
    ).addParametersItem(pathParameter(ViewStorePaths.DEFINITION_ID))
        .addParametersItem(header(CommandComponent.Header.REQUEST_ID, false))
        .addParametersItem(header(CommandComponent.Header.AGGREGATE_VERSION, false, IntegerSchema()))
        .addParametersItem(header(CommandComponent.Header.WAIT_STAGE, false))
        .requestBody(
            RequestBody().required(true).content(
                Content().addMediaType(JSON, MediaType().schema(context.schema(ViewPreferencesInput::class.java)))
            )
        )

    private fun claim(context: OpenAPIComponentContext, errorInfo: Schema<*>): Operation = operation(
        "view-store.claimView",
        "Make a shared view personal to the path's owner (the caller's own path)",
        errorInfo,
        ok(context.schema(CommandResult::class.java)),
    ).addParametersItem(pathParameter(ViewStorePaths.ID))
        .addParametersItem(header(CommandComponent.Header.REQUEST_ID, false))
        .addParametersItem(header(CommandComponent.Header.AGGREGATE_VERSION, false, IntegerSchema()))
        .addParametersItem(header(CommandComponent.Header.WAIT_STAGE, false))

    private fun replay(context: OpenAPIComponentContext, errorInfo: Schema<*>): Operation = operation(
        "view-store.replay",
        "The view as the write with this request id left it; 204 when that write deleted it",
        errorInfo,
        ok(context.schema(MaterializedSnapshot::class.java, ViewState::class.java)),
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
