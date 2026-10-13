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

package me.ahoo.wow.kernel

import java.util.ServiceLoader

/**
 * The binding index of one module (decision D107): the aggregate models of its bounded context [context], the
 * handlers of its subscription processors ([processors], decision D149), and where its Manifest fragment is
 * ([fragment], `META-INF/wow/manifest/<module>.json`). The compiler generates one per module
 * and registers it in `META-INF/services/me.ahoo.wow.kernel.ModelProvider`, so the host finds every module of the
 * service with [load], without scanning or reflecting (decision D37).
 */
interface ModelProvider {
    /** The bounded context of the module's aggregates. */
    val context: String

    /** The class path resource of the module's Manifest fragment. */
    val fragment: String

    /**
     * The module's aggregate models, with their factories' parameters from [dependencies]; a
     * [MissingDependencyException] when one is missing.
     */
    fun models(dependencies: Dependencies): List<AggregateModel<*>>

    /**
     * The handlers of the module's `@Projection`, `@Reactor` and `@Saga` processors (decision D149), which subscribe to
     * [context]: each processor constructed once, with its constructor's parameters from [dependencies] (qualified by
     * their `@Name`, decision D119); a [MissingDependencyException] when one is missing. None by default: the
     * compiler overrides it for a module that declares processors.
     */
    fun processors(dependencies: Dependencies): List<ProcessorBinding<*>> = emptyList()

    companion object {
        /** The model providers registered on [classLoader], ordered by [fragment]. */
        @JvmStatic
        @JvmOverloads
        fun load(classLoader: ClassLoader? = Thread.currentThread().contextClassLoader): List<ModelProvider> =
            ServiceLoader.load(ModelProvider::class.java, classLoader).sortedBy { it.fragment }
    }
}
