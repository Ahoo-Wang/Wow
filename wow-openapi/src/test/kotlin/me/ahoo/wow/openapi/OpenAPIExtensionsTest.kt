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

import io.swagger.v3.oas.models.info.Info
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Wow
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.OpenAPIExtensions.WOW_CONTEXT_ALIAS
import me.ahoo.wow.openapi.OpenAPIExtensions.WOW_CONTEXT_NAME
import me.ahoo.wow.openapi.OpenAPIExtensions.WOW_VERSION
import me.ahoo.wow.openapi.OpenAPIExtensions.withExtensions
import org.junit.jupiter.api.Test

internal class OpenAPIExtensionsTest {

    private val context = MaterializedNamedBoundedContext("test-service")

    @Test
    fun `should add wow version extension to info`() {
        val info = Info().withExtensions(context)
        info.extensions[WOW_VERSION].assert().isEqualTo(Wow.VERSION)
    }

    @Test
    fun `should add context name extension to info`() {
        val info = Info().withExtensions(context)
        info.extensions[WOW_CONTEXT_NAME].assert().isEqualTo("test-service")
    }

    @Test
    fun `should add context alias extension to info`() {
        val info = Info().withExtensions(context)
        info.extensions[WOW_CONTEXT_ALIAS].assert().isEqualTo("test-service")
    }
}
