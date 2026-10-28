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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.log.TailPolling
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * What a [StoreProvider] opens one store instance from: the store's [name] and [type], how it manages
 * the framework's schema ([ddl], `wow.stores.<name>.ddl` over `wow.ddl`), its own settings ([options]), how the tails
 * of its logs poll ([polling], `wow.log.poll-interval`), the [service] whose failure ledger it keeps (the key of its
 * entries), the application's [resources], whether the node runs for [development], where a store
 * may accept a deployment it refuses for production (a standalone MongoDB server, say), and the [clock] its ports read
 * the time from: the leases' and the reservations' expiry, the store's one time source, so a test fixes it in one place.
 *
 * Fields may be added: the host builds it, so the constructor is internal to Wow; a provider's test
 * builds one with [of].
 */
@WowSpi
class StoreConfig @InternalWowApi constructor(
    val name: String,
    val type: String,
    val ddl: Ddl = Ddl.AUTO,
    val options: Map<String, String> = emptyMap(),
    val polling: TailPolling = TailPolling.DEFAULT,
    val service: String = "",
    val resources: StoreResources = StoreResources.NONE,
    val development: Boolean = false,
    val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(name.isNotBlank()) { "A store needs a name." }
        require(type.isNotBlank()) { "A store needs a type." }
    }

    /** The configuration key of this store's settings, `wow.stores.<name>`, for a provider's failures to name. */
    val key: String get() = "wow.stores.$name"

    private val read: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The setting [name] of [options], or null when it is not set or blank. */
    fun option(name: String): String? {
        read += name
        return options[name]?.takeIf { it.isNotBlank() }
    }

    /**
     * The value of [option]: its setting in [options], parsed, else its default; fails naming the setting when the
     * value is not one of the option's type.
     */
    fun <T : Any> option(option: ProviderOption<T>): T? = setting(option) ?: option.default

    /** The value of the [ProviderOption.Defaulted] [option]: its setting, parsed, else its default, so always one. */
    fun <T : Any> option(option: ProviderOption.Defaulted<T>): T = setting(option) ?: option.default

    private fun <T : Any> setting(option: ProviderOption<T>): T? =
        option(option.key)?.let { option.parse(it, "$key.${option.key}") }

    /** The settings under the map [option] ([ProviderOption.map]), their keys relative to it. */
    fun options(option: ProviderOption<Map<String, String>>): Map<String, String> {
        read += option.key
        return options.filterKeys { it.startsWith("${option.key}.") }.mapKeys { it.key.removePrefix("${option.key}.") }
    }

    /** The keys of [options] the store asked for, so a test can hold them to the options its provider declares. */
    @InternalWowApi
    val optionsRead: Set<String> get() = read.toSet()

    override fun toString(): String = "StoreConfig(name=$name, type=$type, ddl=$ddl, options=$options)"

    companion object {
        /** A configuration of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            type: String,
            ddl: Ddl = Ddl.AUTO,
            options: Map<String, String> = emptyMap(),
            polling: TailPolling = TailPolling.DEFAULT,
            service: String = "",
            resources: StoreResources = StoreResources.NONE,
            development: Boolean = false,
            clock: Clock = Clock.systemUTC(),
        ): StoreConfig = StoreConfig(name, type, ddl, options, polling, service, resources, development, clock)
    }
}

/**
 * The application's objects a store is opened over (a `DataSource`, a `MongoClient`): in the Spring Boot host, its
 * beans.
 */
@WowSpi
interface StoreResources {
    /** Whether the application has an object of [type]. */
    fun has(type: Class<*>): Boolean

    /**
     * The application's object of [type] that [name] names, else its only (or primary) one; fails naming [key], the
     * setting that names it, when there is none.
     */
    fun <T : Any> resource(type: Class<T>, name: String?, key: String): T

    companion object {
        /** No resources: a store that needs none (memory), or a test's. */
        @JvmField
        val NONE: StoreResources = of(emptyMap())

        /** The [objects] by name, each of its own class. */
        @JvmStatic
        fun of(objects: Map<String, Any>): StoreResources = object : StoreResources {
            override fun has(type: Class<*>): Boolean = objects.values.any(type::isInstance)

            override fun <T : Any> resource(type: Class<T>, name: String?, key: String): T {
                val found = if (name.isNullOrBlank()) {
                    objects.values.filter(type::isInstance).let {
                        check(it.size == 1) { "$key is not set, and there is not exactly one ${type.simpleName}." }
                        it.single()
                    }
                } else {
                    checkNotNull(objects[name]?.takeIf(type::isInstance)) {
                        "$key names $name, which is no ${type.simpleName}."
                    }
                }
                return type.cast(found)
            }
        }
    }
}
