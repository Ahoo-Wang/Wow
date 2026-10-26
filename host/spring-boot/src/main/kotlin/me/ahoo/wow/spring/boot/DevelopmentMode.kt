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

/**
 * Whether the node runs for development: a profile of `wow.internal.dev-profiles` (`dev`, `local`, `test`, `demo`)
 * is active, or its internal port binds a loopback address alone. It is the one exemption the host's safeguards
 * share, so a laptop starts as it is and a deployment cannot: `wow.backend=auto` falls back to memory, the public port
 * serves without a `CallerResolver` bean (decision D301), and a platform service may take its callers from request
 * headers (review S-9). [reason] says why, null outside development.
 */
class DevelopmentMode(val reason: String?) {
    /** Whether the node runs for development. */
    val active: Boolean get() = reason != null

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
            "$key=$value $does: only a node in development may (a profile of wow.internal.dev-profiles, or an " +
                "internal port on the loopback interface alone).",
            "Unset $key outside development.",
        )
    }

    override fun toString(): String = "DevelopmentMode(${reason ?: "off"})"

    companion object {
        /** No development: a deployment. */
        @JvmField
        val OFF: DevelopmentMode = DevelopmentMode(null)
    }
}
