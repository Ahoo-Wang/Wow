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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.Manifest

/**
 * The build's check of the published streams declared to reveal their `DISPLAY` values
 * (`@AggregateRoot(revealsDisplay = true)`): each must publish one, in its state, unless the stream leaves the state
 * out, or in an event's body, as the schemas of the contract mark them (`x-wow-sensitive` of level `display`, followed
 * through every reference). A declaration that reveals nothing would tell a consumer it receives raw values it never
 * does, and would reveal the first `DISPLAY` field added later without a word in the contract's change.
 */
internal object RevealedDisplays {
    private const val DISPLAY = "display"
    private const val LEVEL = "level"

    /** A problem for each stream of [contract] declared to reveal its `DISPLAY` values that publishes none. */
    fun problems(contract: Manifest): List<String> = contract.contexts.flatMap { context ->
        context.published.filter { it.revealsDisplay }.mapNotNull { stream ->
            val aggregate = context.aggregates.firstOrNull { it.name == stream.aggregate } ?: return@mapNotNull null
            val refs = listOfNotNull(aggregate.state.schema.takeIf { stream.includeState }) +
                aggregate.events.map { it.schema }
            if (refs.any { holdsDisplay(it, contract.defs) }) {
                null
            } else {
                "${context.name}.${aggregate.name} declares @AggregateRoot(revealsDisplay = true), but its published " +
                    (if (stream.includeState) "state and events hold" else "events hold (its state is not published)") +
                    " no DISPLAY value: remove revealsDisplay, or mark the value " +
                    "@Sensitive(SensitivityLevel.DISPLAY)."
            }
        }
    }

    /** Whether the schema [ref] points to marks a `DISPLAY` value anywhere, through the references it holds. */
    private fun holdsDisplay(ref: String, defs: Map<String, Any?>): Boolean {
        val visited = mutableSetOf<String>()
        fun holds(node: Any?): Boolean = when (node) {
            is Map<*, *> -> (node[QueryKeywords.SENSITIVE_KEYWORD] as? Map<*, *>)?.get(LEVEL) == DISPLAY ||
                node.values.any(::holds)

            is List<*> -> node.any(::holds)
            is String -> Manifest.isSchemaRef(node) && Manifest.schemaKey(node).let { key ->
                visited.add(key) && holds(defs[key])
            }

            else -> false
        }
        return holds(ref)
    }
}
