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

package me.ahoo.wow.query.mask

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * A masked read answers only what its schema declares (#7243): a property of a closed object (a class, or a schema
 * of `properties` and no `additionalProperties`) that the schema does not declare is removed from a state, from an
 * event's body at a name and revision the node knows, and from a scheduled command, on the read paths only. An open
 * object, a typed map's or a free one, keeps its properties; a writer's mask removes nothing.
 */
class DeclaredOnlyTest {
    private val type = ManifestFixtures.ORDER

    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                type.context,
                aggregates = listOf(
                    AggregateManifest(
                        type.name,
                        state = StateManifest(Manifest.schemaRef("S")),
                        commands = listOf(CommandManifest("Remind", schema = Manifest.schemaRef("Remind"))),
                        events = listOf(EventManifest("Ordered", schema = Manifest.schemaRef("Ordered"))),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "S" to classOf(
                "S",
                "phone" to mapOf("type" to "string", "x-wow-sensitive" to mapOf("level" to "confidential")),
                "sku" to STRING,
                "line.no" to STRING,
                "line" to ref("Line"),
                "lines" to mapOf("type" to "array", "items" to ref("Line")),
                "byKey" to mapOf("type" to "object", "additionalProperties" to ref("Line")),
                "attrs" to mapOf("type" to "object", "additionalProperties" to emptyMap<String, Any?>()),
                "free" to mapOf("type" to "object"),
                "extras" to ref("Open"),
            ),
            "Line" to classOf("Line", "qty" to mapOf("type" to "integer")),
            "Open" to classOf("Open", "code" to STRING) + ("additionalProperties" to true),
            "Ordered" to mapOf("type" to "object", "properties" to mapOf("sku" to STRING)),
            "Remind" to mapOf("type" to "object", "properties" to mapOf("note" to STRING)),
            "Plain" to classOf("Plain", "sku" to STRING, "line" to ref("Line")),
        ),
    )

    private val masks = PayloadMasks(manifest)

    private fun json(text: String): JsonNode = WowJson.mapper.readTree(text)

    @Test
    fun `a state read removes what its closed objects do not declare, and keeps what open ones hold`() {
        val stored = json(
            """{"phone":"13812345678","sku":"s","line.no":"n-1","card":"s3cr3t",""" +
                """"line":{"qty":1,"pin":"s3cr3t"},"lines":[{"qty":2,"pin":"s3cr3t"}],""" +
                """"byKey":{"k":{"qty":3,"pin":"s3cr3t"}},"attrs":{"a":{"b":1}},"free":{"x":{"y":2}},""" +
                """"extras":{"code":"c","more":{"z":3}}}""",
        )
        masks.state(type, stored).toString().assert().isEqualTo(
            """{"phone":"***********","sku":"s","line.no":"n-1","line":{"qty":1},"lines":[{"qty":2}],""" +
                """"byKey":{"k":{"qty":3}},"attrs":{"a":{"b":1}},"free":{"x":{"y":2}},""" +
                """"extras":{"code":"c","more":{"z":3}}}""",
        )
    }

    @Test
    fun `a state with nothing undeclared is answered as the same nodes`() {
        val stored = json("""{"phone":"13812345678","sku":"s","line":{"qty":1},"lines":[{"qty":2}]}""")
        val line = stored["line"]
        val lines = stored["lines"]
        val read = masks.state(type, stored)
        read.assert().isSameAs(stored)
        read["line"].assert().isSameAs(line)
        read["lines"].assert().isSameAs(lines)
        read["lines"][0].assert().isSameAs(lines[0])
    }

    @Test
    fun `an event's body at a name and revision the node knows answers only what its schema declares`() {
        val body = json("""{"sku":"s","added":"s3cr3t"}""")
        masks.event(type, "${type.context}.${type.name}.Ordered", 1, body).toString()
            .assert().isEqualTo("""{"sku":"s"}""")
        // At a revision the node does not know, the body is masked whole, as before.
        masks.event(type, "Ordered", 2, json("""{"sku":"s"}""")).isNull.assert().isTrue()
    }

    @Test
    fun `a scheduled command answers only what its command type's schema declares`() {
        val body = json(
            """{"commandType":"${type.context}.${type.name}.Remind","command":{"note":"n","added":"s3cr3t"}}""",
        )
        masks.event(type, Schedules.SCHEDULE_SET, 1, body)["command"].toString()
            .assert().isEqualTo("""{"note":"n"}""")
    }

    @Test
    fun `a writer's masks remove nothing, and a state of nothing sensitive has none`() {
        val sensitive = checkNotNull(masks.stateMaskOf(type, revealsDisplay = false))
        sensitive(json("""{"phone":"13812345678","card":"c"}""")).toString()
            .assert().isEqualTo("""{"phone":"***********","card":"c"}""")
        masks.eventMaskOf(type, "Ordered", revealsDisplay = false).assert().isNull()
        val scheduled = checkNotNull(masks.eventMaskOf(type, Schedules.SCHEDULE_SET, revealsDisplay = false))
        scheduled(json("""{"commandType":"${type.context}.${type.name}.Remind","command":{"note":"n","x":1}}"""))
            .toString().assert().contains(""""x":1""")
        // A state of nothing sensitive has no writer's mask: a sink publishes its text as it is.
        PayloadMasks(manifest.withState("Plain")).stateMaskOf(type, revealsDisplay = false).assert().isNull()
        PayloadMasks(manifest.withState("Plain")).state(type, json("""{"sku":"s","x":1}""")).toString()
            .assert().isEqualTo("""{"sku":"s"}""")
    }

    @Test
    fun `the query schema keeps the declared properties, and an open class's other ones as a map of unknown values`() {
        val schema = runBlocking { ManifestFixtures.schemaOf(ManifestQuerySchemaSource(manifest)) }
        val state = checkNotNull(schema.root.properties["state"])
        state.declaredNames.assert().containsExactlyInAnyOrder(
            "phone", "sku", "line.no", "line", "lines", "byKey", "attrs", "free", "extras",
        )
        state.properties.keys.assert().doesNotContain("line.no")
        checkNotNull(state.properties["line"]).declaredNames.assert().containsExactly("qty")
        listOf("byKey", "attrs", "free").forEach { name ->
            checkNotNull(state.properties[name]).declaredNames.assert().describedAs(name).isNull()
        }
        val extras = checkNotNull(state.properties["extras"])
        extras.declaredNames.assert().isNull()
        extras.properties.keys.assert().containsExactly("code")
        checkNotNull(extras.additionalProperties).kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
        checkNotNull(state.properties["attrs"]?.additionalProperties).kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
        schema.field(QueryField("state.extras.code")).assert().isNotNull()
    }

    private fun Manifest.withState(schema: String) = Manifest(
        contexts = contexts.map { context ->
            ContextManifest(
                context.name,
                aggregates = context.aggregates.map { aggregate ->
                    AggregateManifest(aggregate.name, state = StateManifest(Manifest.schemaRef(schema)))
                },
            )
        },
        defs = defs,
    )

    private companion object {
        val STRING = mapOf("type" to "string")

        fun ref(name: String) = mapOf("\$ref" to Manifest.schemaRef(name))

        fun classOf(name: String, vararg properties: Pair<String, Any?>): Map<String, Any?> =
            mapOf("type" to "object", "x-wow-class" to "shop.$name", "properties" to mapOf(*properties))
    }
}
