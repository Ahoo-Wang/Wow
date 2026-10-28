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

package me.ahoo.wow.spi.config

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One setting a provider (a store's, a dataset backend's) reads from its configuration, as it declares it: its [key],
 * what it means ([description], the configuration reference's text), the [type] of its value and its [default]
 * (`null` when it has none), and where it may be set ([scope]). A provider lists every option it reads, and reads
 * each through it, so the host binds, checks and documents exactly those: an option of [Scope.TYPE] is set for every
 * instance of the provider's type under `wow.<type>.<key>`, and each instance may set it over that; a key a provider
 * does not declare fails the start, naming the declared ones.
 *
 * A provider declares its options with the factories ([string], [duration], [int], [boolean], [map]). A [map] option
 * is a prefix whose sub-keys pass through as they are (`properties.*` of a client): a config reads it with its
 * `options(option)`.
 */
@WowSpi
class ProviderOption<T : Any> @InternalWowApi constructor(
    val key: String,
    val description: String,
    val type: Class<T>,
    val default: T?,
    val scope: Scope,
    private val parser: (String) -> T,
) {
    init {
        require(KEY.matches(key)) { "An option key is kebab-case words separated by dots: [$key]." }
        require(description.isNotBlank()) { "Option [$key] needs a description." }
    }

    /** Where an option may be set. */
    enum class Scope {
        /** For every instance of the provider's type (`wow.<type>.<key>`), and each instance over that. */
        TYPE,

        /**
         * Only on one store instance (`wow.stores.<name>.options.<key>`), such as the name of the resource it opens
         * over; only stores have instances of their own, so other providers read it as [TYPE].
         */
        PER_STORE,
    }

    /** Whether this option is a prefix of free sub-keys ([map]). */
    val isMap: Boolean get() = type == Map::class.java

    /** This option, settable only on one store instance ([Scope.PER_STORE]). */
    fun perStore(): ProviderOption<T> = ProviderOption(key, description, type, default, Scope.PER_STORE, parser)

    /** [text] as this option's value; fails naming [where], the key it was set under, when it is not one. */
    fun parse(text: String, where: String): T = try {
        parser(text.trim())
    } catch (failure: IllegalArgumentException) {
        throw WowConfigurationException(
            where,
            text,
            "$where is [$text], which is not a ${type.simpleName} (${failure.message}).",
            "Set it to $description",
        )
    }

    override fun toString(): String = "ProviderOption(key=$key, type=${type.simpleName}, default=$default, scope=$scope)"

    companion object {
        private val KEY = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)*")

        /** A text setting. */
        @JvmStatic
        @JvmOverloads
        fun string(key: String, description: String, default: String? = null): ProviderOption<String> =
            ProviderOption(key, description, String::class.java, default, Scope.TYPE) { it }

        /**
         * A duration, written as Kotlin writes one (`500ms`, `5s`, `1m 30s`), in ISO-8601 (`PT5S`), or as a bare number
         * of milliseconds (`500`), as a Spring `Duration` property reads it.
         */
        @JvmStatic
        @JvmOverloads
        fun duration(key: String, description: String, default: Duration? = null): ProviderOption<Duration> =
            ProviderOption(key, description, Duration::class.java, default, Scope.TYPE, ::parseDuration)

        /** A whole number. */
        @JvmStatic
        @JvmOverloads
        fun int(key: String, description: String, default: Int? = null): ProviderOption<Int> =
            ProviderOption(key, description, Int::class.javaObjectType, default, Scope.TYPE) { text ->
                requireNotNull(text.toIntOrNull()) { "not a whole number" }
            }

        /** `true` or `false`. */
        @JvmStatic
        @JvmOverloads
        fun boolean(key: String, description: String, default: Boolean? = null): ProviderOption<Boolean> =
            ProviderOption(key, description, Boolean::class.javaObjectType, default, Scope.TYPE) { text ->
                requireNotNull(text.lowercase().toBooleanStrictOrNull()) { "neither true nor false" }
            }

        /**
         * The settings under [prefix], each sub-key passed through as it is (a client's `properties.*`, a server's
         * `settings.*`): any sub-key is accepted, none is checked.
         */
        @JvmStatic
        fun map(prefix: String, description: String): ProviderOption<Map<String, String>> {
            @Suppress("UNCHECKED_CAST")
            val type = Map::class.java as Class<Map<String, String>>
            return ProviderOption(prefix, description, type, null, Scope.TYPE) {
                throw IllegalArgumentException("a map option is read by its sub-keys")
            }
        }

        private fun parseDuration(text: String): Duration =
            text.toLongOrNull()?.milliseconds ?: Duration.parse(text)
    }
}
