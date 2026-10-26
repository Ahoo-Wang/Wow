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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `wow.contexts.include` / `exclude` (decision D169). */
class ContextSelectionTest {
    private class Provider(override val context: String) : ModelProvider {
        override val binding: Int = ModelProvider.BINDING
        override val fragment: String = "META-INF/wow/manifest/$context.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()
    }

    private val providers = listOf(Provider("eshop-basket"), Provider("eshop-catalog"), Provider("eshop-ordering"))

    private fun ContextSelection.contexts(): List<String> = select(providers).map { it.context }

    @Test
    fun `no selection should serve every context on the class path`() {
        ContextSelection().isAll.assert().isTrue()
        ContextSelection().contexts().assert().containsExactly("eshop-basket", "eshop-catalog", "eshop-ordering")
    }

    @Test
    fun `an include should serve only the contexts it names`() {
        ContextSelection(include = setOf("eshop-catalog")).contexts().assert().containsExactly("eshop-catalog")
    }

    @Test
    fun `an exclude should serve every other context`() {
        ContextSelection(exclude = setOf("eshop-catalog")).contexts().assert()
            .containsExactly("eshop-basket", "eshop-ordering")
    }

    @Test
    fun `an exclude should remove contexts from the include`() {
        ContextSelection(include = setOf("eshop-catalog", "eshop-basket"), exclude = setOf("eshop-ordering"))
            .contexts().assert().containsExactly("eshop-basket", "eshop-catalog")
    }

    @Test
    fun `a context not on the class path should be refused`() {
        assertThrows<IllegalStateException> { ContextSelection(include = setOf("eshop-payment")).contexts() }
            .message.assert()
            .contains("wow.contexts names eshop-payment")
            .contains("[eshop-basket, eshop-catalog, eshop-ordering]")
        assertThrows<IllegalStateException> { ContextSelection(exclude = setOf("payment")).contexts() }
            .message.assert().contains("wow.contexts names payment")
    }

    @Test
    fun `a context both included and excluded should be refused`() {
        assertThrows<IllegalStateException> {
            ContextSelection(include = setOf("eshop-catalog"), exclude = setOf("eshop-catalog")).contexts()
        }.message.assert().contains("both includes and excludes eshop-catalog")
    }

    @Test
    fun `a selection that leaves no context should be refused`() {
        assertThrows<IllegalStateException> {
            ContextSelection(exclude = setOf("eshop-basket", "eshop-catalog", "eshop-ordering")).contexts()
        }.message.assert().contains("selects no context")
    }

    @Test
    fun `the host should serve the included context`() {
        startHost("wow.contexts.include" to BANK).use { host ->
            host.node.contexts.keys.assert().containsExactly(BANK)
        }
    }

    @Test
    fun `the host should not start on a selection of a context not on the class path`() {
        val failure = assertThrows<Exception> { startHost("wow.contexts.include" to "shop") }
        generateSequence<Throwable>(failure) { it.cause }.last().message.assert()
            .contains("wow.contexts names shop, which no domain module on the class path declares")
    }
}
