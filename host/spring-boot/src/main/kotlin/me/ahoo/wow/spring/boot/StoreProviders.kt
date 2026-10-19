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
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.store.StoreProvider
import java.util.Locale
import java.util.ServiceLoader

/**
 * The store types the host knows by name (review R3.3-1): `auto` (`wow.backend` only), and the built-in backends'
 * types. Any other type is a community backend's, served when its [StoreProvider] is on the classpath or a bean.
 */
internal object StoreTypes {
    const val AUTO = "auto"
    const val MEMORY = "memory"
    const val POSTGRES = "postgres"
    const val MONGO = "mongo"

    /** The module of each built-in durable backend, which a failure names when it is missing. */
    val MODULES: Map<String, String> = mapOf(POSTGRES to "wow-postgres", MONGO to "wow-mongo")

    /** [type] as the providers name it: trimmed and lowercase (`MONGO`, as an enum was configured, is `mongo`). */
    fun of(type: String): String = type.trim().lowercase(Locale.ROOT)
}

/**
 * The store providers of the node (review R3.3-1), by type: the memory provider built into the host, those on the
 * classpath (`ServiceLoader`: the built-in PostgreSQL and MongoDB ones, a community backend's), and the application's
 * [StoreProvider] beans, each taking the place of the one of its type before it; and the [StoreQueryProvider]s found
 * the same way, by type (the memory one built in too).
 */
@InternalWowApi
class StoreProviders internal constructor(
    private val stores: Map<String, StoreProvider>,
    private val queries: Map<String, StoreQueryProvider> = emptyMap(),
) {
    /** The types of the providers, built-in first. */
    val types: List<String> get() = stores.keys.toList()

    /** The providers that can open a durable store from the application's resources alone, by type. */
    internal val durable: Collection<StoreProvider> get() = stores.values.filter { it.type != StoreTypes.MEMORY }

    /** The provider of [type], which [key] configures; fails naming the module of a built-in one when it is missing. */
    internal fun of(type: String, key: String): StoreProvider = stores[type] ?: throw IllegalStateException(
        StoreTypes.MODULES[type]?.let { "$key is $type, which needs $it on the classpath." }
            ?: "$key is $type, which no store provider offers: ${types.joinToString()}.",
    )

    /** The query provider of the stores of [type], or null when they are not queried. */
    fun queriesOf(type: String): StoreQueryProvider? = queries[type]

    internal companion object {
        /**
         * The providers [classLoader] finds, then [beans], over [memory]; two of one type from the same source are
         * ambiguous.
         */
        fun load(
            classLoader: ClassLoader?,
            memory: StoreProvider = MemoryStoreProvider(),
            memoryQueries: StoreQueryProvider = MemoryQueryProvider,
            beans: List<StoreProvider> = emptyList(),
            queryBeans: List<StoreQueryProvider> = emptyList(),
        ): StoreProviders {
            val loader = classLoader ?: StoreProviders::class.java.classLoader
            val stores = linkedMapOf(memory.type to memory)
            stores += byType(ServiceLoader.load(StoreProvider::class.java, loader).toList(), "classpath", StoreProvider::type)
            stores += byType(beans, "bean", StoreProvider::type)
            val queries = mapOf(memoryQueries.type to memoryQueries) + byType(
                ServiceLoader.load(StoreQueryProvider::class.java, loader).toList(),
                "classpath",
                StoreQueryProvider::type,
            ) + byType(queryBeans, "bean", StoreQueryProvider::type)
            return StoreProviders(stores, queries)
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
