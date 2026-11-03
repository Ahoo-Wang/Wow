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

package me.ahoo.wow.spi.access

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/** The one form every record stores an aggregate's access attributes in. */
class AccessValuesTest {
    private val access = linkedMapOf(
        "owner" to "alice",
        "space" to null,
        "region" to "east",
        "level" to 3L,
        "public" to true,
        "teams" to listOf("eng", "ops"),
        "grades" to listOf(1L, 2L),
        "none" to emptyList<String>(),
    )

    @Test
    fun `the attributes read back from their JSON as written`() {
        AccessValues.parse(AccessValues.text(access)).assert().isEqualTo(access)
        AccessValues.of(AccessValues.json(access)).assert().isEqualTo(access)
        AccessValues.text(access).assert().isEqualTo(
            """{"owner":"alice","space":null,"region":"east","level":3,"public":true,""" +
                """"teams":["eng","ops"],"grades":[1,2],"none":[]}""",
        )
    }

    @Test
    fun `however they were written, the values read in one form`() {
        AccessValues.parse("""{"teams":["ops","eng","ops",null],"level":3,"flags":[true,false]}""").assert()
            .isEqualTo(mapOf("teams" to listOf("eng", "ops"), "level" to 3L, "flags" to listOf(false, true)))
        val decoded = mapOf("teams" to setOf("ops", "eng"), "level" to 3, "small" to 2.toShort(), "none" to null)
        AccessValues.normalized(decoded).assert()
            .isEqualTo(mapOf("teams" to listOf("eng", "ops"), "level" to 3L, "small" to 2L, "none" to null))
    }

    @Test
    fun `none at all is not stamped, an empty object is stamped with none`() {
        AccessValues.parse(null).assert().isNull()
        AccessValues.of(null).assert().isNull()
        AccessValues.of(WowJson.mapper.nullNode()).assert().isNull()
        AccessValues.of(WowJson.mapper.missingNode()).assert().isNull()
        AccessValues.parse("{}").assert().isEqualTo(emptyMap<String, Any?>())
    }

      fun `a value of no attribute type is refused, naming the attribute`() {
        listOf("""{"ratio":0.5}""" to "ratio", """{"nested":{"a":1}}""" to "nested").forEach { (json, name) ->
            assertThrownBy<IllegalArgumentException> { AccessValues.parse(json) }.hasMessageContaining(name)
        }
        assertThrownBy<IllegalArgumentException> { AccessValues.parse("[1]") }
        val ratio = mapOf("ratio" to 0.5)
        assertThrownBy<IllegalArgumentException> { AccessValues.normalized(ratio) }.hasMessageContaining("ratio")
        assertThrownBy<IllegalArgumentException> { AccessValues.json(mapOf("teams" to listOf(0.5))) }
    }


    }

    @Test
    fun `a stream's attributes lead with the built-in owner and space, which a snapshot's leave out`() {
        val stream = AccessValues.ofStream(mapOf("region" to "east"), Scope("alice"))
        stream.assert().isEqualTo(mapOf("owner" to "alice", "space" to null, "region" to "east"))
        stream.keys.toList().assert().containsExactly("owner", "space", "region")
        AccessValues.declared(stream).assert().isEqualTo(mapOf("region" to "east"))
    }
}
