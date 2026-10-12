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

package me.ahoo.wow.runtime.binding

import me.ahoo.wow.kernel.Dependencies
import java.util.ServiceLoader

/**
 * The subscription binding index of one module, the sibling of its `me.ahoo.wow.kernel.ModelProvider` (decisions
 * D37, D107): the handlers of the module's `@Projection`, `@Reactor` and `@Saga` processors, of its bounded context
 * [context]. The compiler generates one for a module that declares processors and registers it in
 * `META-INF/services/me.ahoo.wow.runtime.binding.SubscriptionProvider`, so the host finds them with [load] and
 * registers them through [SubscriptionBindings], without scanning or reflecting.
 */
interface SubscriptionProvider {
    /** The bounded context the module's processors subscribe to. */
    val context: String

    /**
     * The module's handlers, each processor constructed once with its constructor's parameters from [dependencies]
     * (qualified by their `@Name`, decision D119); a [me.ahoo.wow.kernel.MissingDependencyException] when one is
     * missing.
     */
    fun handlers(dependencies: Dependencies): List<BoundHandler<*>>

    companion object {
        /** The subscription providers registered on [classLoader], ordered by their context and class name. */
        @JvmStatic
        @JvmOverloads
        fun load(classLoader: ClassLoader? = Thread.currentThread().contextClassLoader): List<SubscriptionProvider> =
            ServiceLoader.load(SubscriptionProvider::class.java, classLoader)
                .sortedWith(compareBy({ it.context }, { it.javaClass.name }))
    }
}
