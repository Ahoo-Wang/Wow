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

package me.ahoo.wow.runtime.store

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * The store types known by name (review R3.3-1): `auto` (`wow.backend` only), and the built-in backends' types. Any
 * other type is a community backend's, served when its [StoreProvider] is found.
 */
@InternalWowApi
object StoreTypes {
    const val AUTO: String = "auto"
    const val MEMORY: String = "memory"
    const val POSTGRES: String = "postgres"
    const val MONGO: String = "mongo"

    /** The module of each built-in durable backend, which a failure names when it is missing. */
    val MODULES: Map<String, String> = mapOf(POSTGRES to "wow-postgres", MONGO to "wow-mongo")

    /** [type] as the providers name it: trimmed and lowercase (`MONGO`, as an enum was configured, is `mongo`). */
    fun of(type: String): String = type.trim().lowercase(Locale.ROOT)

    /**
     * The provider of [type] among [providers], which [key] configures; fails naming the module of a built-in one
     * when it is missing.
     */
    fun provider(providers: Map<String, StoreProvider>, type: String, key: String): StoreProvider =
        providers[type] ?: throw IllegalStateException(
            MODULES[type]?.let { "$key is $type, which needs $it on the classpath." }
                ?: "$key is $type, which no store provider offers: ${providers.keys.joinToString()}.",
        )
}

/** Which backend a node runs on (`wow.backend`, decision D110). */
@InternalWowApi
object BackendSelection {
    /**
     * The store type [configured], `auto` resolved by what is available: the one durable provider of [providers] that
     * [resources] are enough for ([StoreProvider.available]: PostgreSQL with a `DataSource` bean, MongoDB with a
     * `MongoClient` bean). Several available is ambiguous, and a type configured but unavailable fails.
     *
     * None available fails too (review R3.5-1): a node in memory loses every committed fact when it stops, and two
     * of them would each own every partition. Auto falls back to memory only with a [memoryOptIn], the reason a
     * development node may keep its data there (see [memoryOptIn] below); a node meant to run in memory names it,
     * `wow.backend=memory`.
     */
    fun select(
        configured: String,
        providers: Map<String, StoreProvider>,
        resources: StoreResources,
        memoryOptIn: String? = null,
    ): String {
        val type = StoreTypes.of(configured)
        if (type == StoreTypes.AUTO) {
            val available = providers.values.filter { it.type != StoreTypes.MEMORY && it.available(resources) }
                .map(StoreProvider::type)
            check(available.size <= 1) {
                "Several backends can back Wow (${available.joinToString()}): set wow.backend to one of them."
            }
            return available.singleOrNull() ?: StoreTypes.MEMORY.also { checkNotNull(memoryOptIn) { NO_DURABLE_BACKEND } }
        }
        val provider = StoreTypes.provider(providers, type, "wow.backend")
        check(type == StoreTypes.MEMORY || provider.available(resources)) {
            "wow.backend=$type needs ${provider.requirement}."
        }
        return type
    }

    /**
     * Why a node may fall back to memory when `wow.backend` is auto and no durable backend is available, or null when
     * it may not: an active profile of [devProfiles] (`wow.internal.dev-profiles`), or an internal port bound to
     * [bindHost], a loopback address, alone, the rule a node without a cluster token follows (decision D226).
     */
    fun memoryOptIn(activeProfiles: Collection<String>, devProfiles: Set<String>, bindHost: String?): String? {
        val dev = activeProfiles.filter { it in devProfiles }
        return when {
            dev.isNotEmpty() -> "profile ${dev.joinToString()}"
            bindHost != null && isLoopback(bindHost) -> "internal port on loopback $bindHost"
            else -> null
        }
    }

    private fun isLoopback(host: String): Boolean = try {
        InetAddress.getByName(host).isLoopbackAddress
    } catch (_: UnknownHostException) {
        false
    }

    const val NO_DURABLE_BACKEND: String =
        "No durable backend for Wow: wow.backend is auto, but neither PostgreSQL (wow-postgres on the classpath and a " +
            "DataSource bean) nor MongoDB (wow-mongo and a MongoClient bean) is available, and a node in memory loses " +
            "every command it committed when it stops (review R3.5-1). Add the backend's module and its DataSource or " +
            "MongoClient, or set wow.backend=memory to keep the data in memory on purpose. A profile of " +
            "wow.internal.dev-profiles, or an internal port bound to the loopback interface alone, also runs in memory."
}
