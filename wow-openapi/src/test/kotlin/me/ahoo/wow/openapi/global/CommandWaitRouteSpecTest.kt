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

package me.ahoo.wow.openapi.global

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import org.junit.jupiter.api.Test

internal class CommandWaitRouteSpecTest {

    private val context = OpenAPIComponentContext.default()

    @Test
    fun `should have post method`() {
        val spec = CommandWaitRouteSpec(context)
        spec.method.assert().isEqualTo(Https.Method.POST)
    }

    @Test
    fun `should have wait path`() {
        val spec = CommandWaitRouteSpec(context)
        spec.path.assert().isEqualTo(CommandWaitRouteSpecFactory.PATH)
    }

    @Test
    fun `should have request body`() {
        val spec = CommandWaitRouteSpec(context)
        spec.requestBody.assert().isNotNull()
    }
}
