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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import me.ahoo.wow.openapi.OpenApiFixtures.kinds
import me.ahoo.wow.openapi.OpenApiFixtures.ref
import me.ahoo.wow.openapi.OpenApiFixtures.responses
import me.ahoo.wow.openapi.OpenApiFixtures.schema
import org.junit.jupiter.api.Test

/** The framework schemas of the document ([WowSchemas]): what the edge writes, and the access attributes. */
class WowSchemasTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `the framework schemas should hold what the edge writes`() {
        OpenApiChecks.validate(document, WowSchemas.PROBLEM, PROBLEM).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.SNAPSHOT, STATE).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.STATE_TRACE, TRACE).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.EVENT_STREAM, STREAM).assert().isEmpty()
        OpenApiChecks.validate(document, WowSchemas.EVENT_STREAM, STREAM.replace(STREAM_ACCESS, "null")).assert()
            .isEmpty()
        OpenApiChecks.validate(document, "wow.lifecycle.TransferOwner", "{}").assert().isNotEmpty()
    }

    @Test
    fun `an aggregate's records should type the access attributes it declares`() {
        val written = ManifestOpenApi.write(ACCESS)
        OpenApiChecks.check(written)

        @Suppress("UNCHECKED_CAST")
        val paths = written["paths"] as Map<String, Map<String, Map<String, Any?>>>
        val declared = mapOf(
            "region" to mapOf("type" to listOf("string", "null")),
            "teams" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "uniqueItems" to true),
            "level" to mapOf("type" to listOf("integer", "null"), "format" to "int64"),
            "open" to mapOf("type" to listOf("boolean", "null")),
        )
        val access = mapOf("type" to "object", "properties" to declared, "additionalProperties" to ACCESS_VALUE)
        val snapshot = mapOf(
            "\$ref" to "#/components/schemas/wow.Snapshot",
            "properties" to mapOf("state" to ref("schemas", "sample.Cart_State"), "access" to access),
        )
        schema(responses(paths.getValue("/shop/cart/{id}/state").getValue("get"))["200"]).assert().isEqualTo(snapshot)
        kinds(paths.getValue("/shop/cart/snapshot/query").getValue("post"))[3].assert().isEqualTo(snapshot)
        schema(responses(paths.getValue("/shop/cart/{id}/state/trace").getValue("get"))["200"]).assert().isEqualTo(
            mapOf(
                "\$ref" to "#/components/schemas/wow.StateTrace",
                "properties" to mapOf(
                    "commits" to mapOf(
                        "type" to "array",
                        "items" to mapOf("properties" to snapshot["properties"]),
                    ),
                ),
            ),
        )
        val builtIn = mapOf(
            "owner" to mapOf("type" to listOf("string", "null")),
            "space" to mapOf("type" to listOf("string", "null")),
        )
        kinds(paths.getValue("/shop/cart/events/query").getValue("post"))[3].assert().isEqualTo(
            mapOf(
                "\$ref" to "#/components/schemas/wow.EventStream",
                "properties" to mapOf(
                    "access" to mapOf(
                        "anyOf" to listOf(access + ("properties" to builtIn + declared), mapOf("type" to "null")),
                    ),
                ),
            ),
        )
        // An aggregate that declares none keeps the framework's records.
        kinds(paths.getValue("/shop/order/events/query").getValue("post"))[3].assert()
            .isEqualTo(ref("schemas", "wow.EventStream"))
    }

    private companion object {
        /** [MANIFEST] with its cart's access attributes declared, one of each type, one of them multiple. */
        val ACCESS = MANIFEST.copyWith(
            contexts = MANIFEST.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { aggregate ->
                        if (aggregate.name != "cart") return@map aggregate
                        aggregate.copyWith(
                            access = listOf(
                                AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING),
                                AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true),
                                AccessAttributeManifest("level", AccessAttributeManifest.Type.INTEGER),
                                AccessAttributeManifest("open", AccessAttributeManifest.Type.BOOLEAN),
                            ),
                        )
                    },
                )
            },
        )

        private val ACCESS_VALUE = mapOf(
            "description" to "An access attribute's value: a string, an integer, a boolean or null; an array " +
                "of them for a multiple one.",
        )

        private const val PROBLEM = """{"type":"urn:wow:error:wow.command.invalid","title":"Invalid","status":400,"retry":"none",
            "detail":"d","code":"wow.command.invalid","violations":[{"path":"p","code":"c","message":"m"}]}"""
        private const val REF = """{"type":{"context":"c","name":"a"},"tenantId":"t","id":"i"}"""
        private const val STATE = """{"tenantId":"t","aggregateId":"i","version":1,"ownerId":"o","spaceId":"s",
            "deleted":false,"access":{"region":"east","teams":["a","b"]},"firstEventTime":1,"lastEventTime":2,
            "operator":{"id":"o","tenantId":"t","crossScope":false},"firstOperator":null,
            "schedules":{"remind":{"type":"at","epochMillis":5}},"state":{}}"""
        private const val TRACE = """{"ref":$REF,"commits":[{"version":1,"commitTime":2,"commandId":"c",
            "requestId":"r","commandType":"t","events":[{"name":"e","revision":1,"body":{}}],
            "scope":{"ownerId":null,"spaceId":null},"deleted":false,"access":{},"state":{},
            "operator":{"id":"o","tenantId":"t","crossScope":false}}]}"""
        private const val STREAM_ACCESS = """{"owner":"o","space":null,"region":"east"}"""
        private const val STREAM = """{"aggregateId":"i","tenantId":"t","version":1,"commitTime":2,"commandId":"c",
            "requestId":"r","commandType":"t","ownerId":"o","spaceId":null,"operator":null,
            "access":$STREAM_ACCESS,"events":[{"name":"e","revision":1,"body":{}}]}"""
    }
}
