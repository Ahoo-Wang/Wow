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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class EventUpgradeTest {
    /** Revision 3 of an event that began as `{"name": "..."}` and then split the name and added a source. */
    data class Renamed(val first: String, val last: String, val source: String)

    class Person(val id: String)

    private val model = aggregate<Person>("test", "person") {
        initial { Person(it.id) }
        evolve<Renamed>(revision = 3) { }
        alias<Renamed>("test.person.NameChanged")
        upgrade<Renamed>(from = 1) { payload ->
            val node = payload as ObjectNode
            val (first, last) = node.remove("name").asString().split(" ")
            node.put("first", first).put("last", last)
        }
        upgrade<Renamed>(from = 2) { payload -> (payload as ObjectNode).put("source", "unknown") }
    }
    private val codec = EventCodec()

    @Test
    fun `an old revision is upgraded one revision at a time`() {
        codec.decode(model, EventRecord("test.person.Renamed", 1, """{"name":"Ada Lovelace"}"""))
            .assert().isEqualTo(Renamed("Ada", "Lovelace", "unknown"))
        codec.decode(model, EventRecord("test.person.Renamed", 2, """{"first":"Ada","last":"Byron"}"""))
            .assert().isEqualTo(Renamed("Ada", "Byron", "unknown"))
    }

    @Test
    fun `a record under a former name is still read`() {
        codec.decode(model, EventRecord("test.person.NameChanged", 3, """{"first":"A","last":"B","source":"s"}"""))
            .assert().isEqualTo(Renamed("A", "B", "s"))
    }

    @Test
    fun `a newer revision than the model reads fails`() {
        assertThrownBy<IllegalStateException> {
            codec.decode(model, EventRecord("test.person.Renamed", 4, "{}"))
        }
    }

    @Test
    fun `an event type missing an upgrade does not build`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<Person>("test", "person") {
                initial { Person(it.id) }
                evolve<Renamed>(revision = 3) { }
                upgrade<Renamed>(from = 1) { it }
            }
        }
    }
}
