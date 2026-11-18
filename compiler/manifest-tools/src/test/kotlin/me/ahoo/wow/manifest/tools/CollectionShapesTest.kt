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
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import me.ahoo.wow.spi.json.StateJson
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.Deque
import java.util.NavigableSet
import java.util.Queue
import java.util.SortedSet
import java.util.TreeSet

/**
 * Every shape of a collection a state holds is an array of its elements in the state schema, as Wow's JSON writes it
 * and as the query fields the compiler generates descend into it: a sorted set as a list, and a property declared as
 * an `Iterable` or a `Sequence`, which holds a list or a sequence, too.
 */
class CollectionShapesTest {
    data class Badge(val code: String = "a") : Comparable<Badge> {
        override fun compareTo(other: Badge): Int = code.compareTo(other.code)
    }

    class Badges : ArrayList<Badge>()

    class BadgesState {
        var list: List<Badge> = listOf(Badge())
        var collection: Collection<Badge> = listOf(Badge())
        var arrayList: ArrayList<Badge> = arrayListOf(Badge())
        var queue: Queue<Badge> = ArrayDeque(listOf(Badge()))
        var deque: Deque<Badge> = ArrayDeque(listOf(Badge()))
        var kotlinDeque: kotlin.collections.ArrayDeque<Badge> = kotlin.collections.ArrayDeque(listOf(Badge()))
        var array: Array<Badge> = arrayOf(Badge())
        var extended: Badges = Badges().apply { add(Badge()) }
        var iterable: Iterable<Badge> = listOf(Badge())
        var mutableIterable: MutableIterable<Badge> = mutableListOf(Badge())
        var iterableOrNull: Iterable<Badge>? = null
        var sequence: Sequence<String> = sequenceOf("a")
        var set: Set<Badge> = setOf(Badge())
        var sortedSet: SortedSet<Badge> = sortedSetOf(Badge())
        var navigableSet: NavigableSet<Badge> = sortedSetOf(Badge())
        var treeSet: TreeSet<Badge> = sortedSetOf(Badge())
        var linkedHashSet: LinkedHashSet<Badge> = linkedSetOf(Badge())
    }

    private val introspector = SchemaIntrospector(javaClass.classLoader, emptyMap())

    @Test
    fun `each collection shape should be an array of its elements, a set's unique`() {
        val completed = introspector.complete(stateFragment(BadgesState::class.java))
        val properties = defOf(completed, BadgesState::class.java)["properties"] as Map<*, *>
        val badges = mapOf("type" to "array", "items" to mapOf("\$ref" to schemaRef(Badge::class.java.name)))
        listOf(
            "list", "collection", "arrayList", "queue", "deque", "kotlinDeque", "array", "extended", "iterable",
            "mutableIterable",
        ).forEach { properties[it].assert().describedAs(it).isEqualTo(badges) }
        listOf("set", "sortedSet", "navigableSet", "treeSet", "linkedHashSet").forEach {
            properties[it].assert().describedAs(it).isEqualTo(badges + ("uniqueItems" to true))
        }
        properties["iterableOrNull"].assert().isEqualTo(badges + ("type" to listOf("array", "null")))
        properties["sequence"].assert().isEqualTo(mapOf("type" to "array", "items" to mapOf("type" to "string")))
        completed.defs.keys.assert().containsExactlyInAnyOrder(BadgesState::class.java.name, Badge::class.java.name)
    }

    @Test
    fun `Wow's JSON should write each as an array, and read a sorted set back as one`() {
        val json = StateJson.mapper.readTree(StateJson.mapper.writeValueAsString(BadgesState()))
        json.properties().filter { it.key != "iterableOrNull" }.forEach { (name, value) ->
            value.isArray.assert().describedAs(name).isTrue()
        }
        json.path("sortedSet").toString().assert().isEqualTo("""[{"code":"a"}]""")
        val read = StateJson.mapper.readValue(json.toString(), BadgesState::class.java)
        read.sortedSet.assert().isInstanceOf(TreeSet::class.java).containsExactly(Badge())
        read.iterable.toList().assert().containsExactly(Badge())
    }
}
