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

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith

/** The Manifest the OpenAPI tests write, and the reads of a document they share. */
internal object OpenApiFixtures {
    fun command(name: String, creation: Creation, mounted: Boolean = false) = CommandManifest(
        name = name,
        schema = Manifest.schemaRef("sample.$name"),
        creation = creation,
        routes = CommandRoutes.of("shop", "cart", name, creation, mounted),
        mounted = mounted,
    )

    val MANIFEST = Manifest(
        service = ServiceInfo("shop-service", "1.2.0"),
        contexts = listOf(
            ContextManifest(
                name = "shop",
                description = "The shop",
                aggregates = listOf(
                    AggregateManifest(
                        name = "cart",
                        state = StateManifest(Manifest.schemaRef("sample.Cart\$State")),
                        commands = listOf(
                            command("CreateCart", Creation.REQUIRED).copyWith(produces = listOf("CartCreated")),
                            command("PutItem", Creation.NONE).copyWith(aliases = listOf("AddItem")),
                            command("Open", Creation.ALLOWED).copyWith(produces = emptyList()),
                            command("Import", Creation.NONE, mounted = true)
                                .copyWith(transformsTo = listOf("shop.cart.PutItem")),
                        ) + listOf(
                            "wow.lifecycle.DeleteAggregate",
                            "wow.lifecycle.TransferSpace",
                            "wow.lifecycle.Archive",
                        ).map { SystemCommands.lifecycle("shop", "cart", it) } + SystemCommands.rebuild("shop", "cart"),
                        queries = QueryRoutes.queries("shop", "cart", snapshots = true) +
                            QueryManifest("custom", QueryRoutes.of("shop", "cart", "custom")),
                    ),
                    AggregateManifest(
                        name = "order",
                        description = "An order",
                        state = StateManifest(Manifest.schemaRef("sample.Item")),
                        queries = QueryRoutes.queries("shop", "order", snapshots = false),
                    ),
                ),
                errors = listOf(
                    ErrorManifest("shop.cart.full", 409, "The cart is full."),
                    ErrorManifest("shop.cart.closed", 422),
                    ErrorManifest(FrameworkErrors.AGGREGATE_EXISTS, 409, "Not the context's."),
                ),
            ),
        ),
        errors = FrameworkErrors.ALL,
        defs = mapOf(
            "sample.Cart\$State" to mapOf(
                "type" to "object",
                "properties" to mapOf("item" to mapOf("\$ref" to Manifest.schemaRef("sample.Item"))),
            ),
            "sample.Cart_State" to mapOf("type" to "object"),
            "sample.Item" to mapOf("type" to "object"),
            "wow.CommandResult" to mapOf("type" to "string", "minLength" to 1),
            "sample.CreateCart" to mapOf("type" to "object"),
            "sample.PutItem" to mapOf("type" to "object"),
            "sample.Open" to mapOf("type" to "object"),
            "sample.Import" to mapOf("type" to "object"),
        ),
    )

    /** The components of [kind] of this document. */
    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.components(kind: String): Map<String, Any?> =
        (this["components"] as Map<String, Any?>)[kind] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun responses(operation: Map<String, Any?>): Map<String, Any?> = operation["responses"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun content(response: Map<String, Any?>): Map<String, Any?> = response["content"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun schema(response: Any?): Any? =
        (content(response as Map<String, Any?>)["application/json"] as Map<String, Any?>)["schema"]

    /** The kinds of answer of a query [operation]: an array, a page, a cursor page, one record, a count, groups. */
    @Suppress("UNCHECKED_CAST")
    fun kinds(operation: Map<String, Any?>): List<Any?> =
        (schema(responses(operation)["200"]) as Map<String, Any?>)["anyOf"] as List<Any?>

    fun ref(kind: String, name: String): Map<String, Any?> = mapOf("\$ref" to "#/components/$kind/$name")
}
