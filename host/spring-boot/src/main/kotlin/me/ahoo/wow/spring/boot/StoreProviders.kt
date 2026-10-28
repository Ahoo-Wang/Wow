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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.store.StoreProvider
import java.util.ServiceLoader

/**
 * The store providers of the node, by type: the memory provider built into the host, those on the
 * classpath (`ServiceLoader`: the built-in PostgreSQL and MongoDB ones, a community backend's), and the application's
 * [StoreProvider] beans, each taking the place of the one of its type before it. A store's queries are its own
 * facet ([me.ahoo.wow.query.store.StoreQueries]).
 */
@InternalWowApi
class StoreProviders internal constructor(
    /** The store providers, by type. */
    val stores: Map<String, StoreProvider>,
) {
    /** Every option the providers declare, which a store sets as `wow.stores.<name>.<key>`. */
    val options: List<ProviderOption<*>> get() = stores.values.flatMap { it.options }

    /** The types of the providers, built-in first. */
    val types: List<String> get() = stores.keys.toList()

    /** The provider of [type], which [key] configures; fails naming the module of a built-in one when it is missing. */
    internal fun of(type: String, key: String): StoreProvider = StoreTypes.provider(stores, type, key)

    internal companion object {
        /**
         * The providers [classLoader] finds, then [beans], over [memory]; two of one type from the same source are
         * ambiguous.
         */
        fun load(
            classLoader: ClassLoader?,
            memory: StoreProvider = MemoryStoreProvider(),
            beans: List<StoreProvider> = emptyList(),
        ): StoreProviders {
            val loader = classLoader ?: StoreProviders::class.java.classLoader
            val stores = linkedMapOf(memory.type to memory)
            stores += byType(ServiceLoader.load(StoreProvider::class.java, loader).toList(), "classpath", StoreProvider::type)
            stores += byType(beans, "bean", StoreProvider::type)
            stores.values.forEach(::requireUnreserved)
            return StoreProviders(stores)
        }

        /** Fails when [provider] declares an option of a key the host reads itself ([WowProperties.Store.RESERVED]). */
        private fun requireUnreserved(provider: StoreProvider) {
            val reserved = provider.options.map { it.key }.filter { it.substringBefore('.') in WowProperties.Store.RESERVED }
            check(reserved.isEmpty()) {
                "The ${provider.type} store provider ${provider.javaClass.name} declares the options $reserved, but " +
                    "wow.stores.<name>.${WowProperties.Store.RESERVED.joinToString("|")} are the host's: rename them."
            }
        }

        private fun <P : Any> byType(providers: List<P>, source: String, type: (P) -> String): Map<String, P> =
            providers.groupBy { StoreTypes.of(type(it)) }.mapValues { (name, found) ->
                check(found.size == 1) {
                    "The ${source}s $found are all providers of the store type $name: keep one of them."
                }
                found.single()
            }
    }
}
