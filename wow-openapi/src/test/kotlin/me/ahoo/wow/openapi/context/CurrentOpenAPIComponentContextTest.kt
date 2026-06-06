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

package me.ahoo.wow.openapi.context

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

internal class CurrentOpenAPIComponentContextTest {

    @AfterEach
    fun tearDown() {
        CurrentOpenAPIComponentContext.current = null
    }

    @Test
    fun `should set and get current context`() {
        val context = OpenAPIComponentContext.default()
        CurrentOpenAPIComponentContext.current = context
        CurrentOpenAPIComponentContext.current.assert().isSameAs(context)
    }

    @Test
    fun `should clear current context when set to null`() {
        CurrentOpenAPIComponentContext.current = OpenAPIComponentContext.default()
        CurrentOpenAPIComponentContext.current = null
        CurrentOpenAPIComponentContext.current.assert().isNull()
    }
}
