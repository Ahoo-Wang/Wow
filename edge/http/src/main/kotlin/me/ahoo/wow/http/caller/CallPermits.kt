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
import me.ahoo.wow.manifest.FrameworkErrors

/**
 * The commands each service may call on the internal port's call routes (decision D226, review A3-2), by service
 * name: `wow.callers.<service>.commands`, command names or patterns.
 *
 * - A **system command**, one in the framework's namespace (`wow.*`: the lifecycle's delete, restore, owner and space
 *   transfers and tags, the snapshot rebuild, the schedules and the jobs), is refused unless an entry that names the
 *   system namespace (`wow.`) matches it, such as `wow.lifecycle.DeleteAggregate` or `wow.lifecycle.*`: a wildcard
 *   alone (`*`, `shop.cart.*`) never opens one.
 * - A **domain command** is allowed when the service's entries name no domain command; otherwise only those they
 *   match: the list then narrows the service to an allowlist.
 *
 * An entry matches a command by its name (`AddItem`, `wow.lifecycle.DeleteAggregate`) or by its name qualified with
 * its context and aggregate (`shop.cart.AddItem`); `*` stands for any characters. A service is the one the request
 * names (`Wow-Service`, [DeclaredServices], decision D300); the lists catch a misconfiguration, not an attacker. A
 * service without entries calls the domain commands alone.
 *
 * The models each service may read on the internal port's read routes ([reads], `wow.callers.<service>.reads`,
 * decision D259): by `context.aggregate.model` (`ordering.order.snapshot`, `ordering.order.events`, and
 * `ordering.order.state` for the state reads) or a dataset's `context.dataset`, `*` standing for any characters. A
 * service without entries reads every model the node serves; with entries, only those they match.
 */
@InternalWowApi
class CallPermits @JvmOverloads constructor(
    services: Map<String, Collection<String>>,
    reads: Map<String, Collection<String>> = emptyMap(),
) {
    private val entries: Map<String, Entries> = services.mapValues { (service, patterns) ->
        require(patterns.none { it.isBlank() }) { "A command of service $service (wow.callers) is blank." }
        Entries(patterns.map(String::trim))
    }

    private val reads: Map<String, List<Regex>> = reads.mapValues { (service, patterns) ->
        require(patterns.none { it.isBlank() }) { "A read of service $service (wow.callers) is blank." }
        patterns.map { regexOf(it.trim()) }
    }

    /**
     * Why [caller], a service, may not read [model] (`context.aggregate.model` or a dataset's `context.dataset`), or
     * null when it may.
     */
    fun readRefusal(caller: Caller, model: String): String? {
        val service = serviceOf(caller)
        val entries = service?.let { reads[it] }.orEmpty()
        return if (entries.isEmpty() || entries.any { it.matches(model) }) {
            null
        } else {
            "Service $service may not read $model: its wow.callers.$service.reads do not list it."
        }
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
                    "wow.callers.${service ?: "<service>"}.commands."
            }
        } else {
            if (entries.domain.isEmpty() || entries.domain.any { it.matches(command) || it.matches(qualified) }) {
                null
            } else {
                "Service $service may not call $qualified: its wow.callers.$service.commands do not list it."
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
        const val COMMAND_FORBIDDEN: String = FrameworkErrors.CALL_COMMAND_FORBIDDEN

        /** The framework's namespace of command names. */
        private const val SYSTEM_PREFIX: String = "wow."

        private val NONE = Entries(emptyList())

        /** The service [caller] is, by name ([DeclaredServices.serviceOf]). */
        fun serviceOf(caller: Caller): String? = DeclaredServices.serviceOf(caller)

        private fun namesSystem(pattern: String): Boolean =
            pattern.startsWith(SYSTEM_PREFIX) || pattern.contains(".$SYSTEM_PREFIX")

        private fun regexOf(pattern: String): Regex =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
    }
}
