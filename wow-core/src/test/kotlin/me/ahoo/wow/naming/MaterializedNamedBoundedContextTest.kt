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

package me.ahoo.wow.naming

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.naming.Materialized
import me.ahoo.wow.configuration.BoundedContext
import org.junit.jupiter.api.Test

class MaterializedNamedBoundedContextTest {

    @Test
    fun `should materialize string as named bounded context`() {
        val boundedContext = "sales".toNamedBoundedContext()

        boundedContext.contextName.assert().isEqualTo("sales")
        (boundedContext is Materialized).assert().isTrue()
    }

    @Test
    fun `should resolve alias from bounded context when alias is not blank`() {
        val namedContext = MaterializedNamedBoundedContext("sales")

        namedContext.getContextAlias(null).assert().isEqualTo("sales")
        namedContext.getContextAlias(BoundedContext(alias = "sls")).assert().isEqualTo("sls")
        namedContext.getContextAlias(BoundedContext(alias = " ")).assert().isEqualTo("sales")
    }
}
