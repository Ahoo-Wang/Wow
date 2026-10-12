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

package me.ahoo.wow.compiler.model

import tools.jackson.module.kotlin.jacksonObjectMapper

/** The coverage report spike H4 reads: per aggregate root and per subscription processor, what was bound and what could not be. */
internal object ModelReport {
    private val mapper = jacksonObjectMapper()

    fun json(bindings: List<AggregateBinding>, subscriptions: List<SubscriptionBinding> = emptyList()): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf(
                "aggregates" to bindings.sortedBy { it.root }.map {
                    mapOf(
                        "root" to it.root,
                        "state" to it.state,
                        "commands" to it.commands.size,
                        "sourcing" to it.sourcing.size,
                        "afters" to it.afters.size,
                        "problems" to it.problems,
                    )
                },
                "subscriptions" to subscriptions.sortedBy { it.processor }.map {
                    mapOf(
                        "processor" to it.processor,
                        "kind" to it.kind,
                        "handlers" to it.handlers.map { handler -> handler.subscription },
                        "problems" to it.problems,
                    )
                },
            ),
        )
}
