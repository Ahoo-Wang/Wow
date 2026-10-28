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

package me.ahoo.wow.clickhouse

import com.clickhouse.client.api.Client
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.store.StoreResources

/**
 * The ClickHouse connection settings (`wow.clickhouse.*`) the Sink and the dataset backend share, and the client-v2
 * [Client] of them. Both find their client by one rule ([clientOf]): the application's [Client] bean when it has one,
 * which Wow never closes; else one of these settings, which each opens and closes itself; with neither, a Sink that
 * is switched on fails the start, naming both ways.
 *
 * An application that wants one shared client, or uses ClickHouse itself, declares the bean over the same settings,
 * bound with Spring's `Binder` (`Bindable.mapOf(String::class.java, String::class.java)` of `wow.clickhouse`), and
 * closes it with its context: `@Bean(destroyMethod = "close") fun clickHouse(...) = ClickHouseClients.of(settings)`.
 */
object ClickHouseClients {
    /** The server's HTTP endpoint. */
    @JvmField
    val ENDPOINT: ProviderOption<String> = ProviderOption.string(
        "endpoint",
        "the ClickHouse server's HTTP endpoint, such as http://localhost:8123: the Sink and the datasets open a " +
            "client of it and its credentials, unless the application has a ClickHouse Client bean, which they use.",
    )

    /** The user name to sign in with. */
    @JvmField
    val USERNAME: ProviderOption.Defaulted<String> = ProviderOption.string(
        "username",
        "the user name to sign in with.",
        "default"
    )

    /** The password to sign in with. */
    @JvmField
    val PASSWORD: ProviderOption.Defaulted<String> = ProviderOption.string(
        "password",
        "the password to sign in with.",
        ""
    )

    /** The database of the Sink's tables and of a dataset whose `table` names none. */
    @JvmField
    val DATABASE: ProviderOption.Defaulted<String> = ProviderOption.string(
        "database",
        "the database of the Sink's tables and of a dataset whose table names none.",
        "default",
    )

    /**
     * A client of the endpoint and credentials of [settings], keys relative to [key] (`endpoint`, `username`,
     * `password`); fails naming `<key>.endpoint` when it is not set.
     */
    @JvmStatic
    @JvmOverloads
    fun of(settings: Map<String, String>, key: String = PREFIX): Client = of(
        requireNotNull(settings[ENDPOINT.key]?.takeIf { it.isNotBlank() }) {
            "$key.${ENDPOINT.key} is not set: set it to the ClickHouse HTTP endpoint."
        },
        settings[USERNAME.key]?.takeIf { it.isNotBlank() } ?: USERNAME.default,
        settings[PASSWORD.key] ?: PASSWORD.default,
    )

    /** A client of [endpoint], signed in as [username] with [password]. */
    internal fun of(endpoint: String, username: String, password: String): Client = Client.Builder()
        .addEndpoint(endpoint)
        .setUsername(username)
        .setPassword(password)
        .build()

    /**
     * The client of [what] (the Sink, the datasets): the application's among [resources], which the result does not
     * close, else one of the endpoint and credentials [setting] reads, which it owns and closes; fails naming both ways
     * under [key] when there is neither.
     */
    internal fun clientOf(
        resources: StoreResources,
        key: String,
        what: String,
        setting: (ProviderOption<String>) -> String?,
    ): OwnedClient {
        if (resources.has(Client::class.java)) {
            return OwnedClient(resources.resource(Client::class.java, null, key), owned = false)
        }
        val endpoint = requireNotNull(setting(ENDPOINT)) {
            "$what needs a ClickHouse client: declare a ClickHouse Client bean, or set $key.${ENDPOINT.key} " +
                "(with ${USERNAME.key} and ${PASSWORD.key})."
        }
        val client = of(endpoint, checkNotNull(setting(USERNAME)), checkNotNull(setting(PASSWORD)))
        return OwnedClient(client, owned = true)
    }

    /** A [client], which closing this closes when Wow [owned] it, and leaves open when it is the application's. */
    internal class OwnedClient(val client: Client, val owned: Boolean) : AutoCloseable {
        override fun close() {
            if (owned) client.close()
        }
    }

    /** The configuration prefix of the settings. */
    const val PREFIX: String = "wow.clickhouse"
}
