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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StoredQueryTypesTest {
    /** An event that saves a sort, as a query type. */
    data class SortSaved(val id: String, val sort: Sort)

    /** A scope of sharing, nesting a projection. */
    data class SharingScope(val name: String, val projection: Projection)

    /** How a state shares: by a filter, or everything. */
    sealed interface Rule {
        data class Filtered(val filter: FilterExpression) : Rule

        data object Everything : Rule
    }

    /** A state holding query types nested, in a list, in a map and behind a sealed type. */
    class SharingState(val id: String) {
        var scope: SharingScope? = null
        var filters: List<FilterExpression> = emptyList()
        var sorts: Map<String, Sort> = emptyMap()
        var rule: Rule = Rule.Everything
    }

    /** A state of the application's own types, and the level of a field's sensitivity, a plain enumeration. */
    class PlainState(val id: String) {
        var names: List<String> = emptyList()
        var scope: Map<String, List<Int>> = emptyMap()
        var level: SensitivityLevel = SensitivityLevel.entries.first()
    }

    /** A command that looks something up, which holds query types freely. */
    data class Lookup(val filter: FilterExpression, val sorts: List<Sort>, val projection: Projection)

    @Test
    fun `a query type should be found where an event or a state holds it, by its path`() {
        StoredQueryTypes.of(SortSaved::class.java).map { it.toString() }.assert().containsExactly(
            "${SortSaved::class.java.name}.sort (${Sort::class.java.name})",
        )
        val state = SharingState::class.java.name
        StoredQueryTypes.of(SharingState::class.java).map { it.path to it.type }.assert().containsExactlyInAnyOrder(
            "$state.scope.projection" to Projection::class.java,
            "$state.filters[]" to FilterExpression::class.java,
            "$state.sorts{}" to Sort::class.java,
            "$state.rule<Filtered>.filter" to FilterExpression::class.java,
        )
        StoredQueryTypes.of(PlainState::class.java).assert().isEmpty()
    }

    @Test
    fun `an event or a state holding a query type should fail the generation, a command not`() {
        val introspector = SchemaIntrospector(javaClass.classLoader)
        val failure = assertThrows<IllegalStateException> {
            introspector.complete(fragment(SharingState::class.java, events = listOf(SortSaved::class.java)))
        }
        failure.message.assert()
            .startsWith("Events and states hold types of Wow's query protocol: ")
            .contains("${SortSaved::class.java.name}.sort (${Sort::class.java.name})")
            .contains("${SharingState::class.java.name}.rule<Filtered>.filter (${FilterExpression::class.java.name})")
            .endsWith("(https://wow.ahoo.me/migrate/source#events-and-state).")
        // A command holds them freely.
        val completed = introspector.complete(fragment(PlainState::class.java, commands = listOf(Lookup::class.java)))
        completed.defs.keys.assert().contains(Lookup::class.java.name)
    }

    private fun fragment(
        state: Class<*>,
        events: List<Class<*>> = emptyList(),
        commands: List<Class<*>> = emptyList(),
    ): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(
                    AggregateManifest(
                        "sharing",
                        state = StateManifest(schemaRef(state.name)),
                        commands = commands.map { CommandManifest(it.simpleName, schema = schemaRef(it.name)) },
                        events = events.map { EventManifest(it.simpleName, schema = schemaRef(it.name)) },
                    ),
                ),
            ),
        ),
    )
}
