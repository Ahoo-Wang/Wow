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

package me.ahoo.wow.openapi.contract

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.context.HttpComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.asHttpComponentContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class HttpComponentTest {

    @Test
    fun `components should be equal by key`() {
        val first = HttpComponent.header("wow.Test") {}
        val second = HttpComponent.header("wow.Test") { description = "other" }
        val other = HttpComponent.header("wow.Other") {}
        val otherKind = HttpComponent.parameter("wow.Test") {}

        first.assert().isEqualTo(first)
        first.assert().isEqualTo(second)
        first.hashCode().assert().isEqualTo(second.hashCode())
        first.assert().isNotEqualTo(other)
        first.assert().isNotEqualTo(otherKind)
        first.assert().isNotEqualTo("wow.Test")
        first.toString().assert().isEqualTo("HttpComponent(key=wow.Test)")
    }

    @Test
    fun `contracts referencing the same component should be equal`() {
        val parameter = HttpParameter(
            name = "id",
            location = HttpParameterLocation.PATH,
            component = HttpComponent.parameter("wow.id") {}
        )

        parameter.assert().isEqualTo(parameter.copy(component = HttpComponent.parameter("wow.id") { name = "id" }))
    }

    @Test
    fun `should reject a blank key`() {
        assertThrows<IllegalArgumentException> {
            HttpComponent.header(" ") {}
        }.message.assert().contains("key must not be blank")
    }

    @Test
    fun `factories should register the component under its key`() {
        val context = OpenAPIComponentContext.default()
        lateinit var buildContext: HttpComponentContext
        buildContext = context.asHttpComponentContext { it.build(context, buildContext) }
        val header = HttpComponent.header("wow.Header") { description = "header" }

        HttpComponent.parameter("wow.Parameter") { name = "p" }.build(context, buildContext).`$ref`.assert()
            .isEqualTo("#/components/parameters/wow.Parameter")
        HttpComponent.requestBody("wow.Body") { description("body") }.build(context, buildContext).`$ref`.assert()
            .isEqualTo("#/components/requestBodies/wow.Body")
        val response = HttpComponent.response("wow.Response") { header("X-Header", it.ref(header)) }
        response.build(context, buildContext).`$ref`.assert().isEqualTo("#/components/responses/wow.Response")

        context.parameters.getValue("wow.Parameter").name.assert().isEqualTo("p")
        context.requestBodies.getValue("wow.Body").description.assert().isEqualTo("body")
        context.headers.getValue("wow.Header").description.assert().isEqualTo("header")
        context.responses.getValue("wow.Response").headers.getValue("X-Header").`$ref`.assert()
            .isEqualTo("#/components/headers/wow.Header")
    }
}
