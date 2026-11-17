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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A service route's body is read as its function's Kotlin type, nulls included (#6283): a null where the type allows
 * none, the body itself or an element of a collection, an array or a map, is a malformed body, and the function is
 * not called; a null the type allows reaches it.
 */
class ServiceRouteBodyNullsTest {
    data class Note(val text: String)

    /** The bodies the routes' functions were called with. */
    private val received = mutableListOf<Any?>()

    private val note = Manifest.refSchema("Note")

    /** Each route's body type and the request schema its Manifest declares. */
    private val routes: Map<String, Pair<KType, Map<String, Any?>>> = mapOf(
        "note" to (typeOf<Note>() to note),
        "nullableNote" to (typeOf<Note?>() to Manifest.nullableSchema(note)),
        "notes" to (typeOf<List<Note>>() to Manifest.arraySchema(note)),
        "nullableNotes" to (typeOf<List<Note?>>() to Manifest.arraySchema(Manifest.nullableSchema(note))),
        "noteArray" to (typeOf<Array<Note>>() to Manifest.arraySchema(note)),
        "nestedNotes" to (typeOf<List<List<Note>>>() to Manifest.arraySchema(Manifest.arraySchema(note))),
        "noteMap" to (typeOf<Map<String, Note>>() to mapOf("type" to "object", "additionalProperties" to note)),
    )

    private val handlers = routes.map { (name, route) ->
        object : ServiceRouteHandler {
            override val context = "shop"
            override val name = name
            override suspend fun handle(call: RouteCall, request: RouteRequest): Any =
                mapOf("received" to request.body<Any?>(route.first).also { received += it })
        }
    }

    private val node = HttpNode(
        manifest = cartManifest().let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        routes = routes.map { (name, route) ->
                            val post = RouteManifest("POST", "/shop/bodies/$name")
                            ServiceRouteManifest(name, post, request = route.second)
                        },
                    )
                },
            )
        },
        serviceRoutes = ServiceRoutes(handlers),
    )

    private fun malformed(route: String, body: String) {
        val answer = node.post("/shop/bodies/$route", body)
        answer.status.assert().withFailMessage { "$route $body: ${answer.status} ${answer.body}" }.isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.ROUTE_MALFORMED)
        answer.body["violations"][0]["path"].asString().assert().isEqualTo("body")
        received.assert().isEmpty()
    }

    private fun accepted(route: String, body: String) {
        val answer = node.post("/shop/bodies/$route", body)
        answer.status.assert().withFailMessage { "$route $body: ${answer.status} ${answer.body}" }.isEqualTo(200)
        received.assert().hasSize(1)
    }

    @Test
    fun `a null body of a non-null type should be malformed`() = malformed("note", "null")

    @Test
    fun `a null body of a non-null list should be malformed`() = malformed("notes", "null")

    @Test
    fun `a null element of a list of non-null elements should be malformed`() = malformed("notes", "[null]")

    @Test
    fun `a null element of an array of non-null elements should be malformed`() = malformed("noteArray", "[null]")

    @Test
    fun `a null element of a nested list should be malformed`() =
        malformed("nestedNotes", """[[{"text":"a"}, null]]""")

    @Test
    fun `a null value of a map of non-null values should be malformed`() =
        malformed("noteMap", """{"a":{"text":"a"},"b":null}""")

    @Test
    fun `a null property of the body's class should stay malformed`() = malformed("note", """{"text":null}""")

    @Test
    fun `a null body of a nullable type should be accepted`() = accepted("nullableNote", "null")

    @Test
    fun `a null element of a list of nullable elements should be accepted`() = accepted("nullableNotes", "[null]")

    @Test
    fun `a body without nulls should be accepted`() {
        accepted("nestedNotes", """[[{"text":"a"}]]""")
        received.single().assert().isEqualTo(listOf(listOf(Note("a"))))
    }
}
