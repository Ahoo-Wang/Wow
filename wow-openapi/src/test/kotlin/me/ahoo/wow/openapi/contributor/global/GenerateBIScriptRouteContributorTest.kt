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

package me.ahoo.wow.openapi.contributor.global

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.bi.BiScriptHeaders
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import org.junit.jupiter.api.Test

internal class GenerateBIScriptRouteContributorTest {
    private val currentContext = MaterializedNamedBoundedContext("example-service")
    private val componentContext = OpenAPIComponentContext.default(false)

    @Test
    fun `should contribute parameterized BI script POST contract`() {
        GenerateBIScriptRouteContributor.id.assert().isEqualTo("global.bi-script")
        val contract = GenerateBIScriptRouteContributor
            .contributeGlobal(currentContext, componentContext)
            .single()

        contract.method.assert().isEqualTo(Https.Method.POST)
        contract.path.assert().isEqualTo(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
        contract.accept.assert().containsExactly(
            Https.MediaType.APPLICATION_SQL,
            Https.MediaType.APPLICATION_JSON,
        )
        contract.requestBody!!.run {
            required.assert().isTrue()
            content.assert().containsExactly(
                HttpContent(
                    Https.MediaType.APPLICATION_JSON,
                    HttpSchema.TypeRef(BiScriptRequest::class.java)
                )
            )
        }
        contract.responses.map { it.statusCode }.assert()
            .containsExactly(
                Https.Code.OK,
                Https.Code.BAD_REQUEST,
                Https.Code.NOT_ACCEPTABLE,
                Https.Code.UNSUPPORTED_MEDIA_TYPE,
                Https.Code.BAD_GATEWAY,
                Https.Code.SERVICE_UNAVAILABLE,
                Https.Code.GATEWAY_TIMEOUT,
            )
        contract.responses.first().content.map(HttpContent::mediaType).assert().containsExactly(
            Https.MediaType.APPLICATION_SQL,
            Https.MediaType.APPLICATION_JSON,
        )
        contract.responses.first().headers.single().run {
            name.assert().isEqualTo(BiScriptHeaders.DIAGNOSTIC_COUNT)
            schema.assert().isEqualTo(HttpSchema.Integer)
        }
        contract.responses.single { it.statusCode == Https.Code.UNSUPPORTED_MEDIA_TYPE }.componentRef.assert()
            .isEqualTo("${Wow.WOW_PREFIX}${CommonComponent.Response.UNSUPPORTED_MEDIA_TYPE_ERROR_CODE}")

        assertInspectionErrorResponses(contract.responses)

        val unsupportedMediaTypeResponse = componentContext.responses[
            "${Wow.WOW_PREFIX}${CommonComponent.Response.UNSUPPORTED_MEDIA_TYPE_ERROR_CODE}"
        ]!!
        unsupportedMediaTypeResponse.headers.assert().containsKey(CommonComponent.Header.ERROR_CODE)
        unsupportedMediaTypeResponse.content.assert().containsKey(Https.MediaType.APPLICATION_JSON)
    }

    private fun assertInspectionErrorResponses(responses: List<HttpResponse>) {
        listOf(
            Https.Code.NOT_ACCEPTABLE,
            Https.Code.BAD_GATEWAY,
            Https.Code.SERVICE_UNAVAILABLE,
            Https.Code.GATEWAY_TIMEOUT,
        ).forEach { statusCode ->
            responses.single { it.statusCode == statusCode }.run {
                description.assert().isNotNull()
                headers.single().run {
                    name.assert().isEqualTo(CommonComponent.Header.ERROR_CODE)
                    componentRef.assert().isEqualTo("${Wow.WOW_PREFIX}${CommonComponent.Header.ERROR_CODE}")
                }
                content.assert().containsExactly(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        HttpSchema.TypeRef(DefaultErrorInfo::class.java),
                    )
                )
            }
        }
    }
}
