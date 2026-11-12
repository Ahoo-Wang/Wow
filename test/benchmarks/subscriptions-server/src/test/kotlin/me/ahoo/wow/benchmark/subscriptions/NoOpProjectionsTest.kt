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

package me.ahoo.wow.benchmark.subscriptions

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.benchmark.transfer.AccountState
import me.ahoo.wow.benchmark.transfer.TransferContext
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NoOpProjectionsTest {
    /** The bindings with `benchmark.projections` at [count]; null for a node without the property's bean. */
    private fun bindings(count: Int?): List<ProcessorBinding<*>> {
        val projections = count?.let(::BenchProjections)
        return NoOpProjections().processors { type, _ -> projections?.takeIf { type.isInstance(it) } }
    }

    @Test
    fun `binds as many projections of the account as the property says, each a subscription of its own`() {
        bindings(4).map { it.name }.assert().containsExactly(
            "NoOpProjection1.onEvent",
            "NoOpProjection2.onEvent",
            "NoOpProjection3.onEvent",
            "NoOpProjection4.onEvent",
        )
        bindings(1).single().apply {
            kind.assert().isEqualTo(FunctionKind.PROJECT)
            stateType.assert().isEqualTo(AccountState::class.java)
            aggregates.assert().isEmpty()
            blocking.assert().isFalse()
        }
    }

    @Test
    fun `binds none at 0 and without the property's bean`() {
        bindings(0).assert().isEmpty()
        bindings(null).assert().isEmpty()
    }

    @Test
    fun `a count outside 0 to MAX is refused`() {
        assertThrows<IllegalArgumentException> { BenchProjections(-1) }
        assertThrows<IllegalArgumentException> { BenchProjections(BenchProjections.MAX + 1) }
    }

    @Test
    fun `it is the benchmark server's context and fragment, and builds no model`() {
        val providers = ModelProvider.load(javaClass.classLoader)
        val own = providers.single { it is NoOpProjections }
        own.context.assert().isEqualTo(TransferContext.NAME)
        own.binding.assert().isEqualTo(ModelProvider.BINDING)
        own.models(Dependencies { _, _ -> null }).assert().isEmpty()
        val generated = providers.single { it !is NoOpProjections && it.context == TransferContext.NAME }
        generated.fragment.assert().isEqualTo(NoOpProjections.FRAGMENT)
        javaClass.classLoader.getResources(NoOpProjections.FRAGMENT).toList().assert().hasSize(1)
    }
}
