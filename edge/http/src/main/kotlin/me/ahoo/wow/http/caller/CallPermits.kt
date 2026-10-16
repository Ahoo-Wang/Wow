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

package me.ahoo.wow.http.caller

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller

/**
 * The commands each service may call on the internal port's call routes (decision D226, review A3-2), by service
 * name: `wow.http.services.<service>.commands`, command names or patterns.
 *
 * - A **system command**, one in the framework's namespace (`wow.*`: the lifecycle's delete, restore, owner and space
 *   transfers and tags, the snapshot rebuild, the schedules and the jobs), is refused unless an entry that names the
 *   system namespace (`wow.`) matches it, such as `wow.lifecycle.DeleteAggregate` or `wow.lifecycle.*`: a wildcard
 *   alone (`*`, `shop.cart.*`) never opens one.
 * - A **domain command** is allowed when the service's entries name no domain command; otherwise only those they
 *   match: the list then narrows the service to an allowlist.
 *
 * An entry matches a command by its name (`AddItem`, `wow.lifecycle.DeleteAggregate`) or by its name qualified with
 * its context and aggregate (`shop.cart.AddItem`); `*` stands for any characters. A service is named by its caller's
 * subject: `service:<name>` for a service token ([ServiceTokens]), the principal's id for a CoSec service principal. A
 * service without entries calls the domain commands alone.
 */
@InternalWowApi
class CallPermits(services: Map<String, Collection<String>>) {
    private val entries: Map<String, Entries> = services.mapValues { (service, patterns) ->
        require(patterns.none { it.isBlank() }) { "A command of service $service (wow.http.services) is blank." }
        Entries(patterns.map(String::trim))
    }

    /**
     * Why [caller], a service, may not call [command] of [aggregate] in [context], or null when it may.
     */
    fun refusal(caller: Caller, context: String, aggregate: String, command: String): String? {
        val service = serviceOf(caller)
        val entries = service?.let { entries[it] } ?: NONE
        val qualified = "$context.$aggregate.$command"
        return if (command.startsWith(SYSTEM_PREFIX)) {
            if (entries.system.any { it.matches(command) || it.matches(qualified) }) {
                null
            } else {
                "Service ${service ?: caller.subject} may not call the system command $qualified: list it in " +
                    "wow.http.services.${service ?: "<service>"}.commands (decision D226)."
            }
        } else {
            if (entries.domain.isEmpty() || entries.domain.any { it.matches(command) || it.matches(qualified) }) {
                null
            } else {
                "Service $service may not call $qualified: its wow.http.services.$service.commands do not list it."
            }
        }
    }

    private class Entries(patterns: List<String>) {
        val system: List<Regex> = patterns.filter(::namesSystem).map(::regexOf)
        val domain: List<Regex> = patterns.filterNot(::namesSystem).map(::regexOf)
    }

    @InternalWowApi
    companion object {
        /** The code of a call to a command the calling service may not call: answered 403. */
        const val COMMAND_FORBIDDEN: String = "wow.call.command-forbidden"

        /** The framework's namespace of command names. */
        private const val SYSTEM_PREFIX: String = "wow."

        private val NONE = Entries(emptyList())

        /** The service [caller] is: its subject, without the prefix of a service token's subject. */
        fun serviceOf(caller: Caller): String? = caller.subject?.removePrefix(ServiceTokens.SUBJECT_PREFIX)

        private fun namesSystem(pattern: String): Boolean =
            pattern.startsWith(SYSTEM_PREFIX) || pattern.contains(".$SYSTEM_PREFIX")

        private fun regexOf(pattern: String): Regex =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
    }
}
