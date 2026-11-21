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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.spi.json.StateJson
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.TimeUnit

/**
 * The shapes a state takes, as `manifest-tools` writes their `$defs`: every container (a map, a list, a map of maps, a
 * map of lists, a list of maps) of every kind of value (an object, a polymorphic type of each inclusion, a value
 * class, a nullable object, an object with a sensitive or a temporal property, an object that holds a polymorphic
 * property). The snapshots `query-keywords/query-shapes.defs.json` (each state's `$defs`) and
 * `query-keywords/query-shapes.samples.json` (one state of each, as Wow's state JSON writes it) are what `:query`'s
 * `GeneratedShapesContractTest` reads: whatever is generated here is read there (#6555).
 */
class QueryShapesTest {
    data class Plain(val name: String, val count: Int)

    /** Polymorphic, its discriminator a property Jackson adds. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Tagged.Circle::class, name = "circle"),
        JsonSubTypes.Type(value = Tagged.Square::class, name = "square"),
    )
    sealed interface Tagged {
        data class Circle(val radius: Int) : Tagged

        data class Square(val side: Int) : Tagged
    }

    /** Polymorphic, its discriminator a property of its own (#6555's `Shape`). */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Shape.Circle::class, name = "CIRCLE"),
        JsonSubTypes.Type(value = Shape.Square::class, name = "SQUARE"),
    )
    sealed interface Shape {
        val type: String

        data class Circle(val radius: Int) : Shape {
            override val type: String = "CIRCLE"
        }

        data class Square(val side: Int) : Shape {
            override val type: String = "SQUARE"
        }
    }

    /** A sealed class, one of its subtypes an object without properties. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "state")
    @JsonSubTypes(
        JsonSubTypes.Type(value = Switch.On::class, name = "on"),
        JsonSubTypes.Type(value = Switch.Off::class, name = "off"),
    )
    sealed class Switch {
        data class On(val since: Long) : Switch()

        data object Off : Switch()
    }

    /** A value class, which Jackson writes as its value: its schema is declared ([OVERRIDES]), as a build declares it. */
    @JvmInline
    value class Sku(val value: String)

    data class Secret(val label: String, @get:Sensitive(SensitivityLevel.CONFIDENTIAL) val token: String)

    data class Timed(@get:QueryTemporal(unit = TimeUnit.SECONDS) val at: Long)

    /** An object holding a polymorphic property: #6555's `Item`. */
    data class Holder(val name: String, val shape: Shape)

    data class InMap(
        val plain: Map<String, Plain>,
        val tagged: Map<String, Tagged>,
        val shape: Map<String, Shape>,
        val switch: Map<String, Switch>,
        val sku: Map<String, Sku>,
        val nullable: Map<String, Plain?>,
        val secret: Map<String, Secret>,
        val timed: Map<String, Timed>,
        val holder: Map<String, Holder>,
    )

    data class InList(
        val plain: List<Plain>,
        val tagged: List<Tagged>,
        val shape: List<Shape>,
        val switch: List<Switch>,
        val sku: List<Sku>,
        val nullable: List<Plain?>,
        val secret: List<Secret>,
        val timed: List<Timed>,
        val holder: List<Holder>,
    )

    data class InNestedMap(
        val plain: Map<String, Map<String, Plain>>,
        val tagged: Map<String, Map<String, Tagged>>,
        val shape: Map<String, Map<String, Shape>>,
        val switch: Map<String, Map<String, Switch>>,
        val sku: Map<String, Map<String, Sku>>,
        val nullable: Map<String, Map<String, Plain?>>,
        val secret: Map<String, Map<String, Secret>>,
        val timed: Map<String, Map<String, Timed>>,
        val holder: Map<String, Map<String, Holder>>,
    )

    data class InMapOfList(
        val plain: Map<String, List<Plain>>,
        val tagged: Map<String, List<Tagged>>,
        val shape: Map<String, List<Shape>>,
        val switch: Map<String, List<Switch>>,
        val sku: Map<String, List<Sku>>,
        val nullable: Map<String, List<Plain?>>,
        val secret: Map<String, List<Secret>>,
        val timed: Map<String, List<Timed>>,
        val holder: Map<String, List<Holder>>,
    )

    data class InListOfMap(
        val plain: List<Map<String, Plain>>,
        val tagged: List<Map<String, Tagged>>,
        val shape: List<Map<String, Shape>>,
        val switch: List<Map<String, Switch>>,
        val sku: List<Map<String, Sku>>,
        val nullable: List<Map<String, Plain?>>,
        val secret: List<Map<String, Secret>>,
        val timed: List<Map<String, Timed>>,
        val holder: List<Map<String, Holder>>,
    )

    @Test
    fun `the defs of every container of every value should be the snapshot the query module reads`() {
        val defs = defsOf()
        defs.assert().isEqualTo(snapshot("query-shapes.defs.json"))
        // The sensitive and temporal properties keep their keywords under every container, a value used once inlined.
        defs.forEach { (state, stateDefs) ->
            mapper.writeValueAsString(stateDefs).assert().describedAs(state)
                .contains("\"x-wow-sensitive\"", "\"x-wow-temporal\"")
        }
    }

    @Test
    fun `a state of every container of every value should be the sample the query module reads`() {
        val samples = SAMPLES.entries.associate { (state, sample) ->
            state.name to mapper.readValue(StateJson.mapper.writeValueAsString(sample), Map::class.java)
        }
        samples.assert().isEqualTo(snapshot("query-shapes.samples.json"))
    }

    private val mapper = jacksonObjectMapper()

    private fun snapshot(name: String): Map<*, *> =
        javaClass.getResourceAsStream("/query-keywords/$name")!!.use { mapper.readValue(it, Map::class.java) }

    companion object {
        /** The schema of [Sku], as `wow { schemaOverrides }` declares a value class's. */
        private val OVERRIDES = mapOf(Sku::class.java.name to """{"type":"string"}""")

        /** The `$defs` `manifest-tools` writes for each state of [SAMPLES], by the state's class name. */
        fun defsOf(): Map<String, Map<String, Any?>> = SAMPLES.keys.associate { state ->
            state.name to SchemaIntrospector(QueryShapesTest::class.java.classLoader, OVERRIDES)
                .complete(QueryKeywordsTest.fragment(state)).defs
        }

        private val plain = Plain("a", 1)
        private val holder = Holder("h", Shape.Circle(3))
        private val secret = Secret("label", "s3cr3t")
        private val timed = Timed(1_700_000_000)

        /** One state of each shape, its values one of each kind (an object and a null for the nullable). */
        val SAMPLES: Map<Class<*>, Any> = mapOf(
            InMap::class.java to InMap(
                mapOf("k1" to plain),
                mapOf("k1" to Tagged.Circle(1), "k2" to Tagged.Square(2)),
                mapOf("k1" to Shape.Circle(1), "k2" to Shape.Square(2)),
                mapOf("k1" to Switch.On(5), "k2" to Switch.Off),
                mapOf("k1" to Sku("s1")),
                mapOf("k1" to plain, "k2" to null),
                mapOf("k1" to secret),
                mapOf("k1" to timed),
                mapOf("k1" to holder),
            ),
            InList::class.java to InList(
                listOf(plain),
                listOf(Tagged.Circle(1), Tagged.Square(2)),
                listOf(Shape.Circle(1), Shape.Square(2)),
                listOf(Switch.On(5), Switch.Off),
                listOf(Sku("s1")),
                listOf(plain, null),
                listOf(secret),
                listOf(timed),
                listOf(holder),
            ),
            InNestedMap::class.java to InNestedMap(
                mapOf("k1" to mapOf("k2" to plain)),
                mapOf("k1" to mapOf("k2" to Tagged.Circle(1))),
                mapOf("k1" to mapOf("k2" to Shape.Square(2))),
                mapOf("k1" to mapOf("k2" to Switch.Off)),
                mapOf("k1" to mapOf("k2" to Sku("s1"))),
                mapOf("k1" to mapOf("k2" to null)),
                mapOf("k1" to mapOf("k2" to secret)),
                mapOf("k1" to mapOf("k2" to timed)),
                mapOf("k1" to mapOf("k2" to holder)),
            ),
            InMapOfList::class.java to InMapOfList(
                mapOf("k1" to listOf(plain)),
                mapOf("k1" to listOf(Tagged.Square(2))),
                mapOf("k1" to listOf(Shape.Circle(1))),
                mapOf("k1" to listOf(Switch.On(5))),
                mapOf("k1" to listOf(Sku("s1"))),
                mapOf("k1" to listOf(null, plain)),
                mapOf("k1" to listOf(secret)),
                mapOf("k1" to listOf(timed)),
                mapOf("k1" to listOf(holder)),
            ),
            InListOfMap::class.java to InListOfMap(
                listOf(mapOf("k1" to plain)),
                listOf(mapOf("k1" to Tagged.Circle(1))),
                listOf(mapOf("k1" to Shape.Square(2))),
                listOf(mapOf("k1" to Switch.Off)),
                listOf(mapOf("k1" to Sku("s1"))),
                listOf(mapOf("k1" to null)),
                listOf(mapOf("k1" to secret)),
                listOf(mapOf("k1" to timed)),
                listOf(mapOf("k1" to holder)),
            ),
        )
    }
}
