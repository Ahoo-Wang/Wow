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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.ProviderSettings
import me.ahoo.wow.spi.store.StoreResources

/**
 * A dataset backend (the ClickHouse one, say): the [QueryBindings] of the datasets it serves, opened from its settings
 * and the application's resources. The host finds it with `ServiceLoader` (`META-INF/services`) and opens the one
 * that is [available]; with several it refuses the start, naming them, and an application's own
 * `QueryBindings<QueryDataset>` bean takes the place of any.
 */
@WowSpi
interface QueryDatasetProvider {
    /** The type of the backend: lowercase, its settings' prefix (`wow.<type>.*`). */
    val type: String

    /**
     * Every setting it reads from its [QueryDatasetConfig.options], each read through its option, for the host to bind
     * and check (`wow.<type>.<key>`): each of [ProviderOption.Scope.TYPE], since a dataset backend has no instances
     * with settings of their own ([ProviderOption.Scope.INSTANCE]).
     */
    val options: List<ProviderOption<*>> get() = emptyList()

    /** Whether [config] lets it serve datasets: its resources are there and its settings do not switch it off. */
    fun available(config: QueryDatasetConfig): Boolean

    /**
     * The bindings of the datasets it serves, over [config]. Bindings that hold resources of their own, a client the
     * provider opened say, are [AutoCloseable]: the host closes them when the node stops. What they use of the
     * application's resources they never close.
     */
    fun open(config: QueryDatasetConfig): QueryBindings<QueryDataset>
}

/**
 * What a [QueryDatasetProvider] opens from: its [type], its settings ([options], `wow.<type>.*` relative to it) and
 * the application's [resources]. Fields may be added: the host builds it, so the constructor is internal to Wow; a
 * provider's test builds one with [of].
 */
@WowSpi
class QueryDatasetConfig @InternalWowApi constructor(
    val type: String,
    val options: Map<String, String> = emptyMap(),
    val resources: StoreResources = StoreResources.NONE,
) {
    /** The configuration key of its settings, `wow.<type>`, for a provider's failures to name. */
    val key: String get() = "wow.$type"

    /** Its settings, under [key]: what its provider reads its options from ([ProviderSettings]). */
    val settings: ProviderSettings = ProviderSettings(key, options)

    override fun toString(): String = "QueryDatasetConfig(type=$type, options=${options.keys})"

    companion object {
        /** A configuration of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            type: String,
            options: Map<String, String> = emptyMap(),
            resources: StoreResources = StoreResources.NONE,
        ): QueryDatasetConfig = QueryDatasetConfig(type, options, resources)
    }
}
