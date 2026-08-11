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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date

class ImmutableDynamicDocumentTest {
    @Test
    fun `unknown mutable and driver-like values should be rejected`() {
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("value" to MutableBox("secret")))
        }
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("value" to Date()))
        }
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("value" to FakeDriverValue("raw")))
        }
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("value" to arrayOf(hashMapOf("field" to "value"))))
        }
    }

    @Test
    fun `arrays should preserve type and deeply snapshot nested binary values`() {
        val binary = byteArrayOf(1, 2)
        val primitive = intArrayOf(3, 4)
        val nestedBinary = byteArrayOf(5, 6)
        val objects = arrayOf<Any?>(mutableMapOf("binary" to nestedBinary), "value")
        val document = ImmutableDynamicDocument.copyOf(
            mapOf(
                "binary" to binary,
                "primitive" to primitive,
                "objects" to objects
            )
        )

        binary[0] = 9
        primitive[0] = 9
        nestedBinary[0] = 9
        objects[1] = "changed"

        assertArrayEquals(byteArrayOf(1, 2), document["binary"] as ByteArray)
        assertArrayEquals(intArrayOf(3, 4), document["primitive"] as IntArray)
        @Suppress("UNCHECKED_CAST")
        val exposedObjects = document["objects"] as Array<Any?>
        exposedObjects.javaClass.assert().isEqualTo(objects.javaClass)
        val nested = exposedObjects[0] as ImmutableDynamicDocument
        assertArrayEquals(byteArrayOf(5, 6), nested["binary"] as ByteArray)
        exposedObjects[1] = "mutated"
        ((document["objects"] as Array<*>)[1]).assert().isEqualTo("value")
    }

    @Test
    fun `all dynamic document views should detach binary values`() {
        val document = ImmutableDynamicDocument.copyOf(
            mapOf(
                "binary" to byteArrayOf(1, 2),
                "nested" to mapOf("binary" to byteArrayOf(3, 4))
            )
        )

        (document["binary"] as ByteArray)[0] = 9
        (document.entries.first { it.key == "binary" }.value as ByteArray)[0] = 9
        (document.values.first { it is ByteArray } as ByteArray)[0] = 9
        (document.getNestedDocument("nested")["binary"] as ByteArray)[0] = 9

        assertArrayEquals(byteArrayOf(1, 2), document["binary"] as ByteArray)
        assertArrayEquals(byteArrayOf(3, 4), document.getNestedDocument("nested")["binary"] as ByteArray)
    }

    @Test
    fun `same immutable content should have stable structural equality and hash`() {
        val first = ImmutableDynamicDocument.copyOf(
            mapOf("values" to arrayOf<Any?>(byteArrayOf(1, 2), intArrayOf(3, 4)))
        )
        val second = ImmutableDynamicDocument.copyOf(
            mapOf("values" to arrayOf<Any?>(byteArrayOf(1, 2), intArrayOf(3, 4)))
        )

        first.assert().isEqualTo(second)
        second.assert().isEqualTo(first)
        repeat(10) {
            first.hashCode().assert().isEqualTo(second.hashCode())
        }

        val scalarMap = mapOf<String, Any?>("value" to "same")
        val scalarDocument = ImmutableDynamicDocument.copyOf(scalarMap)
        (scalarDocument == scalarMap).assert().isTrue()
        (scalarMap == scalarDocument).assert().isTrue()

        val binaryMap = mapOf<String, Any?>("value" to byteArrayOf(1, 2))
        val binaryDocument = ImmutableDynamicDocument.copyOf(binaryMap)
        (binaryDocument == binaryMap).assert().isFalse()
        (binaryMap == binaryDocument).assert().isFalse()
    }

    private data class MutableBox(var value: String)

    private data class FakeDriverValue(val raw: String)
}
