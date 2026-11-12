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

import me.ahoo.wow.benchmark.transfer.AccountState
import me.ahoo.wow.benchmark.transfer.TransferContext
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding

/**
 * The no-op projections of the account: [BenchProjections.count] handlers, `NoOpProjection<n>.onEvent`, each a
 * subscription of its own over the account's log that takes every commit with its state and does nothing with it.
 *
 * Written by hand rather than generated, so that their number is a property and one distribution measures every count.
 * It names the benchmark server's context and Manifest fragment and builds no model: the account's model and the
 * transfer saga stay the generated provider's, and the Manifest, which lists them, is read once. The projections are
 * not in it, which no check of the Manifest compares (it compares models and commands).
 */
class NoOpProjections : ModelProvider {
    override val binding: Int = ModelProvider.BINDING

    override val context: String = TransferContext.NAME

    override val fragment: String = FRAGMENT

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()

    override fun processors(dependencies: Dependencies): List<ProcessorBinding<*>> {
        val count = (dependencies.find(BenchProjections::class.java, null) as BenchProjections?)?.count ?: 0
        return (1..count).map { n ->
            ProcessorBinding.project("NoOpProjection$n", "onEvent", AccountState::class.java) { _, _ -> }
        }
    }

    companion object {
        /** The benchmark server's Manifest fragment, named after its project path. */
        const val FRAGMENT: String = "META-INF/wow/manifest/benchmark-server.json"
    }
}
