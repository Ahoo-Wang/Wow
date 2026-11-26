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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The options are a plain class (decision D216) that keeps a data class's equality, hash and string. */
class OpenApiOptionsTest {
    @Test
    fun `an option equals another with the same fields, and prints them`() {
        valueSemantics(OpenApiOptions(), OpenApiOptions(), OpenApiOptions(title = "Shop"))
        val bearer = mapOf("bearer" to OpenApiSecurityScheme.bearer())
        valueSemantics(
            OpenApiOptions().withSecuritySchemes(bearer),
            OpenApiOptions(title = OpenApiOptions.DEFAULT_TITLE).withSecuritySchemes(bearer),
            OpenApiOptions(),
        )
        OpenApiOptions().withSecuritySchemes(bearer).securitySchemes.assert().isEqualTo(bearer)
    }

    @Test
    fun `what the host adds should be its options', and a header the framework names is left to the framework`() {
        fun hosted() = OpenApiOptions(
            requestFacts = setOf("X-Region", "traceparent", "CoSec-Request-Id"),
            requestIdHeaders = listOf("CoSec-Request-Id", "Wow-Wait"),
            filterCodes = listOf("wow.request.rate-limited"),
        )
        val options = hosted()
        valueSemantics(options, hosted(), OpenApiOptions())
        options.withSecuritySchemes(emptyMap()).filterCodes.assert().containsExactly("wow.request.rate-limited")
        // A request id header first, then the facts; the framework's own parameters keep their names.
        OpenApiParameters.host(options).map { it.key }.assert().containsExactly("CoSec-Request-Id", "X-Region")
    }

    @Test
    fun `a host's header should be named once, ignoring case, and written inline when its name is no component key`() {
        val options = OpenApiOptions(
            requestFacts = linkedSetOf("if-match", "cosec-request-id", "X-Region", "x-region", "X~Region", "Wow!Tag"),
            requestIdHeaders = listOf("CoSec-Request-Id"),
        )
        val hosted = OpenApiParameters.host(options)
        // If-Match is the framework's, cosec-request-id the request id header's, x-region X-Region's.
        hosted.map { it.parameter["name"] }.assert()
            .containsExactly("CoSec-Request-Id", "X-Region", "X~Region", "Wow!Tag")
        hosted.map { it.key }.assert().containsExactly("CoSec-Request-Id", "X-Region", null, null)
        val document = ManifestOpenApi.write(EShopOpenApiSnapshotTest.manifest("catalog"), options)
        OpenApiChecks.check(document)
        val parameters = (document["components"] as Map<*, *>)["parameters"] as Map<*, *>
        parameters.keys.assert().contains("CoSec-Request-Id", "X-Region").doesNotContain("X~Region", "Wow!Tag")
    }

    @Test
    fun `a filter code the document cannot describe should be refused, naming it`() {
        val catalog = EShopOpenApiSnapshotTest.manifest("catalog")
        assertThrows<IllegalArgumentException> {
            ManifestOpenApi.write(catalog, OpenApiOptions(filterCodes = listOf("shop.no-such-code")))
        }.message.assert().contains("OpenApiOptions.filterCodes names [shop.no-such-code]")
        ManifestOpenApi.write(catalog, OpenApiOptions(filterCodes = listOf("wow.request.rate-limited")))
    }

    @Test
    fun `a security scheme equals another of the same fields, and prints them`() {
        valueSemantics(
            OpenApiSecurityScheme.bearer("JWT"),
            OpenApiSecurityScheme.bearer("JWT"),
            OpenApiSecurityScheme.header("X-User"),
        )
    }

    @Test
    fun `a header security scheme should name its header`() {
        assertThrows<IllegalArgumentException> { OpenApiSecurityScheme.header(" ") }
    }

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
