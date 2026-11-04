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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

/**
 * The 9.x store's tags, counted and sampled by MongoDB ([MongoTags]) as the sources' own walk counts them
 * ([NineXEventSource.tags], [NineXSnapshotSource.tags]): an array without nulls, a lone value as one, null as none.
 */
class MongoNineXTagsTest {
    private val order = NineXAggregate("example-service", "order")
    private val database = NineXMongoStore.example()
    private val streams = MongoNineXEventSource(database)
    private val snapshots = MongoNineXSnapshotSource(database)

    private val shipped = NineXExample.resource("order-stream-v4.json")
    private val shippedEvent =
        """{"id":"0VY3GwcK4OmV00R","name":"order_shipped","revision":"0.0.1","bodyType":"me.ahoo.wow.example.api.order.OrderShipped","body":{}}"""

    /** The JSON of the 9.x stream [id] of the order [aggregateId] at [version], holding [events]. */
    private fun stream(id: String, aggregateId: String, version: Int, vararg events: String): String = shipped
        .replace("\"0VY3GwcK4OmV00Q\"", "\"$id\"")
        .replace("0VY3GwaK4OmV00E", aggregateId)
        .replace("\"version\":4", "\"version\":$version")
        .replace(shippedEvent, events.joinToString(","))

    /** The 9.x tags event of [tags], a JSON object, as 9.x wrote it. */
    private fun tagsEvent(id: String, tags: String): String =
        """{"id":"$id","name":"${MapDrafter.TAGS_EVENT}","revision":"0.0.1","bodyType":"me.ahoo.wow.modeling.DefaultResourceTagsApplied","body":{"tags":$tags}}"""

    private fun snapshot(aggregateId: String, tags: String): String = NineXExample.resource("order-snapshot.json")
        .replace("0VY3GwaK4OmV00E", aggregateId)
        .replace("\"tags\":{}", "\"tags\":$tags")

    private val texts = listOf(
        stream("tags-5", "0VY3GwaK4OmV00E", 5, tagsEvent("e-5", """{"region":["west"],"team":["a","b"]}""")),
        // A retag: 9.x replaced the whole map.
        stream("tags-6", "0VY3GwaK4OmV00E", 6, tagsEvent("e-6", """{"region":["east","north"],"level":["x"],"flag":["TRUE"]}""")),
        // An event of the user's holding tags, one whose tags are no object, and a clear.
        stream(
            "tags-1",
            "tagged-order",
            1,
            """{"id":"noted","name":"order_noted","body":{"note":"n","tags":{"lone":"v","none":null,"level":[3,null]}}}""",
            """{"id":"listed","name":"order_listed","body":{"tags":["x"]}}""",
            tagsEvent("e-1", "{}"),
        ),
        // Another context's, in the same collection.
        stream("other-tags", "other-order", 5, tagsEvent("e-o", """{"region":["east","west"]}"""))
            .replace("example-service", "other-service"),
    )

    private val snapshotTexts = listOf(
        snapshot("tagged-order", """{"region":["west","east"],"channel":["web"],"level":"7"}"""),
        snapshot("z-tagged", """{"channel":["app"],"region":null}"""),
        snapshot("other-order", """{"region":["south","north"]}""").replace("example-service", "other-service"),
    )

    init {
        texts.forEach { NineXMongoStore.insert(database, it) }
        snapshotTexts.forEach { NineXMongoStore.saveSnapshot(database, it) }
    }

    /** The same store in memory, whose tags the sources' own walk counts. */
    private val inMemoryStreams = InMemoryNineXEventSource(NineXExample.streams() + texts.map(NineXStream::parse))
    private val inMemorySnapshots =
        InMemoryNineXSnapshotSource(NineXExample.snapshots() + snapshotTexts.map(NineXSnapshot::parse), inMemoryStreams)

    @Test
    fun `the tags events and their keys count as the walk counts them, within the context`() {
        val tags = streams.tags(order)
        tags.events.assert().isEqualTo(mapOf(MapDrafter.TAGS_EVENT to 3L, "order_noted" to 1L))
        tags.applied.assert().isEqualTo(
            mapOf(
                "region" to TagValueStats(count = 2, multiple = 1, notInteger = 2, notBoolean = 2),
                "team" to TagValueStats(count = 1, multiple = 1, notInteger = 1, notBoolean = 1),
                "level" to TagValueStats(count = 2, multiple = 0, notInteger = 1, notBoolean = 2),
                "flag" to TagValueStats(count = 1, multiple = 0, notInteger = 1, notBoolean = 0),
                "lone" to TagValueStats(count = 1, multiple = 0, notInteger = 1, notBoolean = 1),
                "none" to TagValueStats(count = 1),
            ),
        )
        inMemoryStreams.tags(order).assert().isEqualTo(tags)
        streams.tags(NineXAggregate("example-service", "cart")).assert().isEqualTo(NineXTags.NONE)
    }

    @Test
    fun `the snapshots' tag keys count as the walk counts them, within the context`() {
        val tags = snapshots.tags(order)
        tags.assert().isEqualTo(
            mapOf(
                "region" to TagValueStats(count = 2, multiple = 1, notInteger = 1, notBoolean = 1),
                "channel" to TagValueStats(count = 2, multiple = 0, notInteger = 2, notBoolean = 2),
                "level" to TagValueStats(count = 1, multiple = 0, notInteger = 0, notBoolean = 1),
            ),
        )
        inMemorySnapshots.tags(order).assert().isEqualTo(tags)
    }

    @Test
    fun `the samples are the records holding the values, as the walk finds them`() {
        fun both(key: String, problem: TagValueProblem, fromSnapshots: Boolean = false): List<String> {
            val mongo = TagReader.of(streams, snapshots).samples(order, key, problem, fromSnapshots)
            TagReader.of(inMemoryStreams, inMemorySnapshots).samples(order, key, problem, fromSnapshots).assert()
                .containsExactlyInAnyOrderElementsOf(mongo)
            return mongo
        }
        both("region", TagValueProblem.MULTIPLE).assert().containsExactly("tags-6")
        both("level", TagValueProblem.NOT_INTEGER).assert().containsExactly("tags-6")
        both("level", TagValueProblem.NOT_BOOLEAN).assert().containsExactly("tags-1", "tags-6")
        both("lone", TagValueProblem.NOT_INTEGER).assert().containsExactly("tags-1")
        both("flag", TagValueProblem.NOT_BOOLEAN).assert().isEmpty()
        both("none", TagValueProblem.NOT_INTEGER).assert().isEmpty()
        both("region", TagValueProblem.MULTIPLE, fromSnapshots = true).assert().containsExactly("tagged-order")
        both("channel", TagValueProblem.NOT_INTEGER, fromSnapshots = true).assert().containsExactly("tagged-order", "z-tagged")
        both("level", TagValueProblem.NOT_INTEGER, fromSnapshots = true).assert().isEmpty()
        streams.tagSamples(order, "level", TagValueProblem.NOT_BOOLEAN, 1).assert().containsExactly("tags-1")
        snapshots.tagSamples(order, "channel", TagValueProblem.NOT_BOOLEAN, 1).assert().containsExactly("tagged-order")
        streams.tagSamples(order, "a.b", TagValueProblem.MULTIPLE, 3).assert().isEmpty()
        snapshots.tagSamples(order, "\$a", TagValueProblem.MULTIPLE, 3).assert().isEmpty()
    }
}
