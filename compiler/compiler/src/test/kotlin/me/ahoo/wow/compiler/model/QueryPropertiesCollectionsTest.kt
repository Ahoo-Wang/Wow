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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * A state property that is a collection of elements, in whatever shape (a sorted set, a deque, an array, a class
 * extending a list, a declared `Iterable`), has its elements' query fields, as the state schema describes it: an
 * array of them. The type's hierarchy decides, not a list of names. A shape that holds no elements a state reads back,
 * a `Sequence` of a class, is a compile error naming the property, never fields left out in silence.
 */
@OptIn(ExperimentalCompilerApi::class)
class QueryPropertiesCollectionsTest {
    private val shapes = SourceFile.kotlin(
        "Shelf.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import java.util.ArrayDeque
        import java.util.Deque
        import java.util.NavigableSet
        import java.util.Queue
        import java.util.SortedSet
        import java.util.TreeSet
        import java.util.concurrent.CopyOnWriteArrayList

        data class Badge(val code: String = "a", val rank: Int = 0) : Comparable<Badge> {
            override fun compareTo(other: Badge): Int = code.compareTo(other.code)
        }
        class Badges : ArrayList<Badge>()
        typealias Ranked<T> = TreeSet<T>
        typealias Keyed<T> = Map<*, T>
        data class Stock(val by: Int)
        data class Stocked(val by: Int)

        @AggregateRoot
        class Shelf(private val state: ShelfState) {
            fun onCommand(command: Stock): Stocked = Stocked(command.by)
        }

        class ShelfState(val id: String) {
            var list: List<Badge> = emptyList()
            var mutableList: MutableList<Badge> = mutableListOf()
            var set: Set<Badge> = emptySet()
            var collection: Collection<Badge> = emptyList()
            var iterable: Iterable<Badge> = emptyList()
            var sortedSet: SortedSet<Badge> = sortedSetOf()
            var treeSet: TreeSet<Badge>? = null
            var navigableSet: NavigableSet<Badge> = TreeSet()
            var linkedHashSet: LinkedHashSet<Badge> = linkedSetOf()
            var hashSet: HashSet<Badge> = hashSetOf()
            var arrayList: ArrayList<Badge> = arrayListOf()
            var copyOnWrite: CopyOnWriteArrayList<Badge> = CopyOnWriteArrayList()
            var queue: Queue<Badge> = ArrayDeque()
            var deque: Deque<Badge> = ArrayDeque()
            var kotlinDeque: kotlin.collections.ArrayDeque<Badge> = kotlin.collections.ArrayDeque()
            var array: Array<Badge> = emptyArray()
            var extended: Badges = Badges()
            var ranked: Ranked<Badge> = TreeSet()
            var nested: List<SortedSet<Badge>> = emptyList()
            var words: Sequence<String> = emptySequence()
            var codes: SortedSet<String> = sortedSetOf()
            var byCode: Map<String, Badge> = emptyMap()
            var keyed: Keyed<Badge> = emptyMap<String, Badge>()
            var anyItems: Iterable<*> = emptyList<Any>()
            var anyCollection: Collection<*> = emptyList<Any>()
            fun onSourcing(event: Stocked) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `every shape of a collection should name its elements' fields`() {
        val (_, result) = compileModel("generate", shapes)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val collections = listOf(
            "list", "mutableList", "set", "collection", "iterable", "sortedSet", "treeSet", "navigableSet",
            "linkedHashSet", "hashSet", "arrayList", "copyOnWrite", "queue", "deque", "kotlinDeque", "array",
            "extended", "ranked", "nested",
        )
        val expected = linkedMapOf("STATE__ID" to "state.id")
        collections.forEach { name ->
            val constant = "STATE__" + name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
            expected[constant] = "state.$name"
            expected["${constant}__CODE"] = "state.$name.code"
            expected["${constant}__RANK"] = "state.$name.rank"
        }
        // A collection of values, a sequence of them, a map and a collection of anything are leaves: their elements
        // have no fields a query could name.
        expected["STATE__WORDS"] = "state.words"
        expected["STATE__CODES"] = "state.codes"
        expected["STATE__BY_CODE"] = "state.byCode"
        expected["STATE__KEYED"] = "state.keyed"
        expected["STATE__ANY_ITEMS"] = "state.anyItems"
        expected["STATE__ANY_COLLECTION"] = "state.anyCollection"
        result.properties("sample.ShelfStateProperties").assert().isEqualTo(expected)
    }

    @Test
    fun `a sorted set's element fields should be named inside its element match`() {
        val query = SourceFile.kotlin(
            "Queries.kt",
            """
            package sample

            import me.ahoo.wow.api.query.dsl.listQuery

            val query = listQuery {
                filter {
                    ShelfStateProperties.STATE__SORTED_SET.elementMatch {
                        ShelfStateProperties.STATE__SORTED_SET__CODE eq "a"
                        ShelfStateProperties.STATE__SORTED_SET__RANK gte 1
                    }
                }
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", shapes, query)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val built = result.classLoader.loadClass("sample.QueriesKt").getMethod("getQuery").invoke(null)
        built.toString().assert().contains("field=state.sortedSet", "field=code", "field=rank")
    }

    @Test
    fun `a Java state's sorted set should name its elements' fields`() {
        val root = SourceFile.kotlin(
            "Rack.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Hang(val by: Int)
            data class Hung(val by: Int)

            @AggregateRoot
            class Rack(private val state: RackState) {
                fun onCommand(command: Hang): Hung = Hung(command.by)
            }
            """.trimIndent(),
        )
        val state = SourceFile.java(
            "RackState.java",
            """
            package sample;

            import java.util.SortedSet;
            import java.util.TreeSet;

            public class RackState {
                public SortedSet<Tag> tags = new TreeSet<>();

                public void onSourcing(Hung event) {
                }

                public static class Tag {
                    public String label;
                }
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", root, state)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.properties("sample.RackStateProperties").assert().isEqualTo(
            linkedMapOf("STATE__TAGS" to "state.tags", "STATE__TAGS__LABEL" to "state.tags.label"),
        )
    }

    @Test
    fun `a sequence of a class should be a compile error naming the property`() {
        val source = SourceFile.kotlin(
            "Feed.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Post(val title: String)
            data class Publish(val title: String)
            data class Published(val title: String)

            @AggregateRoot
            class Feed(private val state: FeedState) {
                fun onCommand(command: Publish): Published = Published(command.title)
            }

            class FeedState {
                var posts: List<Sequence<Post>> = emptyList()
                fun onSourcing(event: Published) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "sample.FeedState.posts is a Sequence of sample.Post: Jackson writes a Sequence as an array but reads " +
                "its elements back as maps, so a state holds none of these, and its query fields could name none " +
                "of their fields; hold them in a List or a Set",
        )
    }

    /** The generated object's own constants, in declaration order, as name to path. */
    private fun JvmCompilationResult.properties(name: String): Map<String, String> {
        val type = classLoader.loadClass(name)
        val instance = type.getField("INSTANCE").get(null)
        val queryField = classLoader.loadClass("me.ahoo.wow.api.query.QueryField")
        val path = queryField.getMethod("getPath")
        return type.declaredFields.filter { it.type == queryField }
            .associateTo(linkedMapOf()) { field ->
                field.isAccessible = true
                field.name to path.invoke(field.get(instance)) as String
            }
    }
}
