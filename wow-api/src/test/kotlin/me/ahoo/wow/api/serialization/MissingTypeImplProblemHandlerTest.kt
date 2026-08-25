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

package me.ahoo.wow.api.serialization

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.databind.json.JsonMapper

class MissingTypeImplProblemHandlerTest {
    private val mapper = JsonMapper.builder()
        .addHandler(MissingTypeImplProblemHandler())
        .build()

    @Test
    fun `explicit handler should use annotated implementation for missing type`() {
        val message = mapper.readValue("""{"value":"fallback"}""", TestMessage::class.java)

        message.assert().isInstanceOf(DefaultMessage::class.java)
        (message as DefaultMessage).value.assert().isEqualTo("fallback")
    }

    @Test
    fun `mapper without handler should preserve Jackson missing type error`() {
        val mapper = JsonMapper.builder().build()

        assertThrows<InvalidTypeIdException> {
            mapper.readValue("""{"value":"missing"}""", TestMessage::class.java)
        }
    }

    @Test
    fun `known type should deserialize and round trip`() {
        val message = mapper.readValue(
            """{"type":"KNOWN","value":"known"}""",
            TestMessage::class.java,
        )
        val roundTripped = mapper.readValue(mapper.writeValueAsString(message), TestMessage::class.java)

        message.assert().isInstanceOf(KnownMessage::class.java)
        (roundTripped as KnownMessage).value.assert().isEqualTo("known")
    }

    @Test
    fun `unknown type should preserve Jackson default error`() {
        assertThrows<InvalidTypeIdException> {
            mapper.readValue("""{"type":"UNKNOWN","value":"unknown"}""", TestMessage::class.java)
        }
    }

    @Test
    fun `missing type without annotation should preserve Jackson error`() {
        assertThrows<InvalidTypeIdException> {
            mapper.readValue("""{"value":"missing"}""", UnannotatedMessage::class.java)
        }
    }

    @MissingTypeImpl(DefaultMessage::class)
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(JsonSubTypes.Type(KnownMessage::class, name = "KNOWN"))
    private interface TestMessage

    private class DefaultMessage : TestMessage {
        var value: String = ""
    }

    private class KnownMessage : TestMessage {
        var value: String = ""
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(JsonSubTypes.Type(UnannotatedKnownMessage::class, name = "KNOWN"))
    private interface UnannotatedMessage

    private class UnannotatedKnownMessage : UnannotatedMessage {
        var value: String = ""
    }
}
