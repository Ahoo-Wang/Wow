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
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import org.junit.jupiter.api.Test

internal class GenerateBIScriptRouteContributorTest {
    private val currentContext = MaterializedNamedBoundedContext("example-service")
    private val componentContext = OpenAPIComponentContext.default(false)

    @Test
    fun `should contribute parameterized BI script POST contract`() {
        val contract = GenerateBIScriptRouteContributor
            .contributeGlobal(currentContext, componentContext)
            .single()

        contract.method.assert().isEqualTo(Https.Method.POST)
        contract.path.assert().isEqualTo(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
        contract.accept.assert().containsExactly(Https.MediaType.APPLICATION_SQL)
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
            .containsExactly(Https.Code.OK, Https.Code.BAD_REQUEST)
        contract.responses.first().content.assert().containsExactly(
            HttpContent(Https.MediaType.APPLICATION_SQL, HttpSchema.String)
        )
    }
}
