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
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import me.ahoo.wow.openapi.OpenApiFixtures.components
import me.ahoo.wow.openapi.OpenApiFixtures.responses
import org.junit.jupiter.api.Test

/**
 * Review r1 (the HTTP edge, OpenAPI and the client): the document is to describe exactly what the routes accept and
 * answer, so that a client generated from it can call every route and read every answer. Each test names what a route
 * of `edge/http` does that the document does not say.
 */
@Suppress("UNCHECKED_CAST")
class R1OpenApiTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    private fun paths(): Map<String, Map<String, Any?>> = document["paths"] as Map<String, Map<String, Any?>>

    private fun operation(method: String, path: String): Map<String, Any?> =
        checkNotNull(paths()[path]?.get(method)) { "No $method $path in ${paths().keys}" } as Map<String, Any?>

    private fun response(name: String): Map<String, Any?> = document.components("responses")[name] as Map<String, Any?>

    private fun parameters(operation: Map<String, Any?>): List<String> =
        (operation["parameters"] as List<*>).map { (it as Map<*, *>)["\$ref"].toString().substringAfterLast('/') }

    @Test
    fun `a retryable 409 or 429 declares the Retry-After its problem carries`() {
        // Problem.respond writes Retry-After for every problem whose retry is not none: wow.command.contended,
        // wow.command.busy, wow.request.rate-limited (Problem.kt); the document declares it on the 503 alone.
        listOf("shop.command.409", "shop.command.429").forEach { name ->
            (response(name)["headers"] as Map<String, Any?>?)?.keys.orEmpty().assert()
                .describedAs("the headers of $name")
                .contains("Retry-After") // fails: none
        }
    }

    @Test
    fun `a command route lists every status it answers`() {
        val create = responses(operation("post", "/shop/cart/create-cart"))
        // A body over the codecs' limit is 413 (Problem.tooLargeProblem, reference/http.md); If-Match on a command
        // that names no id is read, so `If-Match: *` on a creation is 412 (RequestHeaders.expectedVersion).
        create.keys.assert().contains("413", "412") // fails: neither
        // Every public route refuses a Wow- header it does not take (400) and a Wow-Call (403): PublicRequests.
        responses(operation("get", "/wow/openapi")).keys.assert().contains("400", "403") // fails: neither
    }

    @Test
    fun `every header a route reads is one of its parameters`() {
        // Wow-Space is read on every route (Authenticator), If-Match on every command route (RequestHeaders).
        parameters(operation("get", "/shop/cart/{id}/state")).assert().contains("Wow-Space") // fails
        parameters(operation("post", "/shop/cart/create-cart")).assert().contains("If-Match") // fails
    }

    @Test
    fun `the problem schema describes the version a rejection answers`() {
        // Problem.members writes `version` on a rejection: the next If-Match (reference/http.md).
        val problem = document.components("schemas")["wow.Problem"] as Map<String, Any?>
        (problem["properties"] as Map<String, Any?>).keys.assert().contains("version") // fails
    }
}
