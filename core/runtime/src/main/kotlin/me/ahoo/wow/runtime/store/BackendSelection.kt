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
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * The store types known by name: `auto` (`wow.backend` only), and the built-in backends' types. Any
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
        providers[type] ?: throw WowConfigurationException(
            key,
            type,
            MODULES[type]?.let { "The store type $type needs $it on the classpath." }
                ?: "No store provider offers the store type $type: ${providers.keys.joinToString()}.",
            MODULES[type]?.let { "Add $it to the dependencies." } ?: "Name a store type a provider offers.",
        )
}

/** Which backend a node runs on (`wow.backend`). */
@InternalWowApi
object BackendSelection {
    /**
     * The store type [configured], `auto` resolved by what is available: the one durable provider of [providers] that
     * [resources] are enough for ([StoreProvider.available]: PostgreSQL with a `DataSource` bean, MongoDB with a
     * `MongoClient` bean). Several available is ambiguous, and a type configured but unavailable fails.
     *
     * None available fails too: a node in memory loses every committed fact when it stops, and two
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
            configCheck(available.size <= 1, BACKEND, configured, "Name one of them.") {
                "Several backends can back Wow: ${available.joinToString()}."
            }
            return available.singleOrNull() ?: StoreTypes.MEMORY.also {
                configNotNull(memoryOptIn, BACKEND, NO_DURABLE_BACKEND_FIX, configured) { NO_DURABLE_BACKEND }
            }
        }
        val provider = StoreTypes.provider(providers, type, BACKEND)
        configCheck(
            type == StoreTypes.MEMORY || provider.available(resources),
            BACKEND,
            type,
            "Give it what it needs, or name another backend."
        ) {
            "The backend $type needs ${provider.requirement}."
        }
        return type
    }

    /**
     * Why a node may fall back to memory when `wow.backend` is auto and no durable backend is available, or null when
     * it may not: an active profile of [devProfiles] (`wow.dev-profiles`), or an internal port bound to
     * [bindHost], a loopback address, alone, the development exemption the host's safeguards share.
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

    /** The key of the node's backend. */
    const val BACKEND: String = "wow.backend"

    const val NO_DURABLE_BACKEND: String =
        "No durable backend for Wow: the backend is auto, but neither PostgreSQL (wow-postgres on the classpath and a " +
            "DataSource) nor MongoDB (wow-mongo and a MongoClient) is available, and a node in memory loses " +
            "every command it committed when it stops."

    const val NO_DURABLE_BACKEND_FIX: String =
        "Add the backend's module and its DataSource or MongoClient, or name the memory backend to keep the data in " +
            "memory on purpose. A development profile, or an internal port bound to the loopback interface alone, " +
            "also runs in memory."
}
