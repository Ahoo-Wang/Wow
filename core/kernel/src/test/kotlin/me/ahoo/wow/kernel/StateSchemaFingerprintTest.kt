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

package me.ahoo.wow.kernel

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The state schema fingerprint follows the state's JSON shape and nothing else. */
class StateSchemaFingerprintTest {
    data class Line(val sku: String, val quantity: Int)
    enum class Status { OPEN, CLOSED }
    enum class StatusWithMore { OPEN, CLOSED, ARCHIVED }

    data class Order(val id: String, val lines: List<Line>, val status: Status, val notes: Map<String, String>)

    /** [Order] with its properties declared in another order. */
    data class Reordered(val status: Status, val notes: Map<String, String>, val lines: List<Line>, val id: String)
    data class Added(
        val id: String,
        val lines: List<Line>,
        val status: Status,
        val notes: Map<String, String>,
        val total: Long
    )
    data class Removed(val id: String, val lines: List<Line>, val status: Status)
    data class Renamed(val id: String, val items: List<Line>, val status: Status, val notes: Map<String, String>)
    data class Retyped(val id: Long, val lines: List<Line>, val status: Status, val notes: Map<String, String>)

    data class WiderLine(val sku: String, val quantity: Int, val price: Long)
    data class NestedChanged(
        val id: String,
        val lines: List<WiderLine>,
        val status: Status,
        val notes: Map<String, String>
    )
    data class EnumChanged(
        val id: String,
        val lines: List<Line>,
        val status: StatusWithMore,
        val notes: Map<String, String>
    )

    /** Serialized names count, not Kotlin names; ignored properties do not count. */
    data class Annotated(
        @param:JsonProperty("id") val identifier: String,
        val lines: List<Line>,
        val status: Status,
        val notes: Map<String, String>,
        @get:JsonIgnore val transient: Int = 0,
    )

    data class Node(val name: String, val children: List<Node>)

    private val codec = EventCodec()

    private fun <S : Any> fingerprint(stateType: Class<S>, codec: EventCodec = this.codec): String =
        codec.stateSchemaFingerprint(aggregate(stateType, "test", "state") { initial { error("unused") } })

    private val order = fingerprint(Order::class.java)

    @Test
    fun `the same class gives the same fingerprint every time`() {
        fingerprint(Order::class.java, EventCodec()).assert().isEqualTo(order)
        order.assert().hasSize(64)
    }

    @Test
    fun `the fingerprint does not depend on declaration order or class names`() {
        fingerprint(Reordered::class.java).assert().isEqualTo(order)
        fingerprint(Annotated::class.java).assert().isEqualTo(order)
    }

    @Test
    fun `adding, removing, renaming or retyping a property changes the fingerprint`() {
        val changed = listOf(Added::class.java, Removed::class.java, Renamed::class.java, Retyped::class.java)
            .map { fingerprint(it) }
        changed.forEach { it.assert().isNotEqualTo(order) }
        changed.toSet().assert().hasSize(changed.size)
    }

    @Test
    fun `a change in a held type changes the fingerprint`() {
        fingerprint(NestedChanged::class.java).assert().isNotEqualTo(order)
        fingerprint(EnumChanged::class.java).assert().isNotEqualTo(order)
    }

    @Test
    fun `the described shape is canonical`() {
        codec.stateShape(Order::class.java).assert().isEqualTo(
            "{id:java.lang.String,lines:java.util.List<{quantity:int,sku:java.lang.String}>," +
                "notes:java.util.Map<java.lang.String,java.lang.String>,status:enum(CLOSED,OPEN)}",
        )
    }

    @Test
    fun `a recursive state is described with a back-reference`() {
        codec.stateShape(Node::class.java).assert().isEqualTo("{children:java.util.List<^2>,name:java.lang.String}")
    }

    @Test
    fun `the cart's state has a fingerprint`() {
        codec.stateShape(CartState::class.java).assert().isEqualTo(
            "{id:java.lang.String,items:java.util.Map<java.lang.String,java.lang.Integer>,owner:java.lang.String}",
        )
    }
}
