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

package me.ahoo.wow.spring.boot.config

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.cluster.NodeAddress
import me.ahoo.wow.runtime.store.BackendSelection
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.core.env.Environment

/**
 * Whether the node runs for development: a profile of `wow.dev-profiles` (`dev`, `local`, `test`, `demo`)
 * is active, or the operator named a loopback address alone for its internal port (`wow.internal.host`, else the host
 * of `wow.node.address`). It is the one exemption the host's safeguards share, so a laptop starts as it is and a
 * deployment cannot: `wow.backend=auto` falls back to memory, the public port serves without a `CallerResolver` bean,
 * and, in a development profile alone ([profiles]), takes its callers from request headers. [reason] says why, null
 * outside development.
 *
 * It is read from what the operator wrote, never from a resolved default: a node whose internal port binds the
 * address this host's name resolves to is a deployment, even where that is a loopback address (`127.0.1.1` in Debian's
 * `/etc/hosts`).
 *
 * The profiles in effect are the active ones, or Spring's default profiles (`spring.profiles.default`) when none is
 * active, as Spring itself takes them: [profiles] are those of `wow.dev-profiles` among them, empty when the node runs
 * for development by a loopback internal port its operator named alone.
 */
class DevelopmentMode(val reason: String?, val profiles: List<String> = emptyList()) {
    /** Whether the node runs for development. */
    val active: Boolean get() = reason != null

    /** Whether a profile of `wow.dev-profiles` is in effect: an explicit choice to run for development. */
    val byProfile: Boolean get() = profiles.isNotEmpty()

    /**
     * Refuses a setting meant for development alone outside it: a [WowConfigurationException] of [key] set to [value],
     * which [does] what no deployment may.
     */
    fun require(key: String, value: Any?, does: String) {
        if (active) {
            return
        }
        throw WowConfigurationException(
            key,
            value,
            "$key=$value $does: only a node in development may (a profile of wow.dev-profiles, or a " +
                "loopback wow.internal.host).",
            "Unset $key outside development.",
        )
    }

    override fun toString(): String = "DevelopmentMode(${reason ?: "off"})"

    companion object {
        /** No development: a deployment. */
        @JvmField
        val OFF: DevelopmentMode = DevelopmentMode(null)

        /** Whether a node of [properties] in [environment] runs for development. */
        @InternalWowApi
        fun of(properties: WowProperties, environment: Environment): DevelopmentMode {
            val effective = environment.activeProfiles.ifEmpty { environment.defaultProfiles }.toList()
            return DevelopmentMode(
                BackendSelection.memoryOptIn(effective, properties.devProfiles, namedHostOf(properties)),
                effective.filter { it in properties.devProfiles },
            )
        }

        /**
         * The interface the operator named for the internal port of a node of [properties]: `wow.internal.host`, else
         * the host of `wow.node.address`; null when they named neither and the node binds a default.
         */
        @InternalWowApi
        fun namedHostOf(properties: WowProperties): String? = properties.internal.host?.takeIf { it.isNotBlank() }
            ?: properties.node.address?.takeIf { it.isNotBlank() }?.let(NodeAddress::hostOf)
    }
}
