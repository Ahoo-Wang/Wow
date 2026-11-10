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
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import me.ahoo.wow.openapi.OpenApiFixtures.ref
import me.ahoo.wow.openapi.OpenApiFixtures.responses
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The error responses of the document ([ErrorResponses]): their statuses, and one registration per name. */
class ErrorResponsesTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `the host's error statuses should win over the tables`() {
        val document = ManifestOpenApi.write(
            MANIFEST,
            OpenApiOptions(
                errorStatuses = mapOf(FrameworkErrors.AGGREGATE_VERSION_MISMATCH to 409, "shop.cart.full" to 400),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val responses = (document["components"] as Map<String, Any?>)["responses"] as Map<String, Map<String, Any?>>
        responses.keys.assert().doesNotContain("shop.command.412")
        (responses.getValue("shop.command.400")["x-wow-error-codes"] as List<*>).assert().contains("shop.cart.full")
        OpenApiChecks.check(document)
    }

    @Test
    fun `a response registered twice with another content should fail`() {
        val responses = ErrorResponses(MANIFEST, emptyMap())
        responses.register("x", mapOf("description" to "one"))
        responses.register("x", mapOf("description" to "one")).assert().isEqualTo(ref("responses", "x"))
        assertThrows<IllegalStateException> { responses.register("x", mapOf("description" to "two")) }
        assertThrows<IllegalStateException> { responses.of("command", emptyList(), domain = true) }
    }
}
