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
 * The binding index of one module: the aggregate models of its bounded context [context], the
 * handlers of its subscription processors ([processors]) and of its service routes ([routes]), and where its Manifest fragment is
 * ([fragment], `META-INF/wow/manifest/<module>.json`). The compiler generates one per module
 * and registers it in `META-INF/services/me.ahoo.wow.kernel.ModelProvider`, so the host finds every module of the
 * service with [load], without scanning or reflecting.
 */
interface ModelProvider {
    /**
     * The binding the module was generated for: [BINDING] of the kernel its compiler was released with. [load] refuses
     * a module of another binding, so a module built by another Wow version fails at startup, not with a
     * `NoSuchMethodError` on its first command. A provider written by hand says [BINDING].
     */
    val binding: Int

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
     * The handlers of the module's `@Projection`, `@Reactor` and `@Saga` processors, which subscribe to
     * [context]: each processor constructed once, with its constructor's parameters from [dependencies] (qualified by
     * their `@Name`); a [MissingDependencyException] when one is missing. None by default: the
     * compiler overrides it for a module that declares processors.
     */
    fun processors(dependencies: Dependencies): List<ProcessorBinding<*>> = emptyList()

    /**
     * The handlers of the module's service routes (`@ServiceRoute`), each bound to the instance of its
     * class that [dependencies] holds; a [MissingDependencyException] when it holds none. None by default: the
     * compiler overrides it for a module that declares routes.
     */
    fun routes(dependencies: Dependencies): List<me.ahoo.wow.kernel.route.ServiceRouteHandler> = emptyList()

    companion object {
        /**
         * The binding between the code the compiler generates and this kernel: raised whenever what the generated
         * code calls changes (the model builder, the processor bindings, [Dependencies]).
         */
        const val BINDING: Int = 4

        /**
         * The model providers registered on [classLoader], ordered by [fragment]; an [IllegalStateException] when one
         * was generated for another [binding].
         */
        @JvmStatic
        @JvmOverloads
        fun load(classLoader: ClassLoader? = Thread.currentThread().contextClassLoader): List<ModelProvider> =
            ServiceLoader.load(ModelProvider::class.java, classLoader).sortedBy { it.fragment }.onEach(::checkBinding)

        /** Fails unless [provider] was generated for this kernel's [BINDING]. */
        @JvmStatic
        fun checkBinding(provider: ModelProvider) {
            // A module generated before the handshake has no binding at all: its class lacks the member.
            val binding = try {
                provider.binding
            } catch (_: AbstractMethodError) {
                null
            }
            check(binding == BINDING) {
                "${provider.javaClass.name} (${provider.fragment}) was generated for binding ${binding ?: "none"} " +
                    "of wow-kernel, which reads binding $BINDING: build the module with the wow-compiler of this " +
                    "Wow version."
            }
        }
    }
}
