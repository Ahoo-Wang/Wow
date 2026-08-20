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
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date
import java.util.IdentityHashMap

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
        MutableEnum.VALUE.mutable = "secret"
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("value" to MutableEnum.VALUE))
        }
    }

    @Test
    fun `maps should reject key cardinality loss without exposing contents`() {
        val root = IdentityHashMap<String, Any?>()
        root[String(charArrayOf('s', 'e', 'c', 'r', 'e', 't'))] = "first-sensitive-value"
        root[String(charArrayOf('s', 'e', 'c', 'r', 'e', 't'))] = "second-sensitive-value"

        val rootError = assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(root)
        }
        val nestedError = assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("nested" to root))
        }

        listOf(rootError, nestedError).forEach { error ->
            error.message.assert().doesNotContain("secret")
            error.message.assert().doesNotContain("sensitive")
        }
        val ordinary = ImmutableDynamicDocument.copyOf(
            linkedMapOf("first" to 1, "nested" to linkedMapOf("second" to 2))
        )
        ordinary.assert().hasSize(2)
        ordinary.getNestedDocument("nested").assert().hasSize(1)
    }

    @Test
    fun `big number subclasses should be rejected while exact base values remain stable`() {
        val mutableDecimal = MutableBigDecimal()
        val mutableInteger = MutableBigInteger()
        mutableDecimal.mutation = 1
        mutableInteger.mutation = 1

        assertAll(
            {
                assertThrows<IllegalArgumentException> {
                    ImmutableDynamicDocument.copyOf(mapOf("value" to mutableDecimal))
                }
            },
            {
                assertThrows<IllegalArgumentException> {
                    ImmutableDynamicDocument.copyOf(mapOf("value" to setOf(mutableDecimal)))
                }
            },
            {
                assertThrows<IllegalArgumentException> {
                    ImmutableDynamicDocument.copyOf(mapOf("value" to mutableInteger))
                }
            },
            {
                assertThrows<IllegalArgumentException> {
                    ImmutableDynamicDocument.copyOf(mapOf("value" to setOf(mutableInteger)))
                }
            }
        )

        val document = ImmutableDynamicDocument.copyOf(
            mapOf("decimal" to BigDecimal("1.25"), "integer" to BigInteger("125"))
        )
        val initialHash = document.hashCode()
        document["decimal"].assert().isEqualTo(BigDecimal("1.25"))
        document["integer"].assert().isEqualTo(BigInteger("125"))
        document.hashCode().assert().isEqualTo(initialHash)
    }

    @Test
    fun `sets should accept only stable equality scalar elements`() {
        val safe = ImmutableDynamicDocument.copyOf(mapOf("values" to linkedSetOf("one", "two")))
        val identityStrings = java.util.Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
        identityStrings.add(String(charArrayOf('s', 'a', 'm', 'e')))
        identityStrings.add(String(charArrayOf('s', 'a', 'm', 'e')))

        (safe["values"] as Set<*>).assert().containsExactly("one", "two")
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(
                mapOf("values" to linkedSetOf(byteArrayOf(1, 2), byteArrayOf(1, 2)))
            )
        }
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("values" to linkedSetOf(listOf("nested"))))
        }
        assertThrows<IllegalArgumentException> {
            ImmutableDynamicDocument.copyOf(mapOf("values" to identityStrings))
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

        val entryIterator = document.entries.iterator()
        val entry = entryIterator.next()
        assertThrows<UnsupportedOperationException> { entry.setValue("changed") }
        assertThrows<UnsupportedOperationException> { entryIterator.remove() }
        assertThrows<UnsupportedOperationException> { document.entries.add(entry) }
        assertThrows<UnsupportedOperationException> { document.entries.remove(entry) }
        assertThrows<UnsupportedOperationException> { document.entries.clear() }
        assertThrows<UnsupportedOperationException> { document.keys.remove("binary") }
        assertThrows<UnsupportedOperationException> { document.values.clear() }

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
        first.entries.assert().isEqualTo(second.entries)
        second.entries.assert().isEqualTo(first.entries)
        repeat(10) {
            first.hashCode().assert().isEqualTo(second.hashCode())
            first.hashCode().assert().isEqualTo(first.entries.hashCode())
            first.entries.hashCode().assert().isEqualTo(second.entries.hashCode())
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

    @Test
    fun `cyclic value graphs should fail fast while shared acyclic graphs remain valid`() {
        val cyclicMap = mutableMapOf<String, Any?>()
        cyclicMap["self"] = cyclicMap
        val cyclicList = mutableListOf<Any?>()
        cyclicList.add(cyclicList)
        val cyclicSet = java.util.Collections.newSetFromMap(IdentityHashMap<Any?, Boolean>())
        cyclicSet.add(cyclicSet)
        val cyclicArray = arrayOfNulls<Any?>(1)
        cyclicArray[0] = cyclicArray

        listOf(cyclicMap, cyclicList, cyclicSet, cyclicArray).forEach { cyclic ->
            assertThrows<IllegalArgumentException> {
                ImmutableDynamicDocument.copyOf(mapOf("cyclic" to cyclic))
            }
        }

        val shared = mutableListOf<Any?>("value")
        val document = ImmutableDynamicDocument.copyOf(mapOf("first" to shared, "second" to shared))
        document.assert().hasSize(2)
    }

    private data class MutableBox(var value: String)

    private data class FakeDriverValue(val raw: String)

    private enum class MutableEnum(var mutable: String) {
        VALUE("initial")
    }

    private class MutableBigDecimal : BigDecimal("1.25") {
        var mutation: Int = 0

        override fun toByte(): Byte = toInt().toByte()

        override fun toShort(): Short = toInt().toShort()

        override fun equals(other: Any?): Boolean = super.equals(other)

        override fun hashCode(): Int = super.hashCode() + mutation
    }

    private class MutableBigInteger : BigInteger("125") {
        var mutation: Int = 0

        override fun toByte(): Byte = toInt().toByte()

        override fun toShort(): Short = toInt().toShort()

        override fun equals(other: Any?): Boolean = super.equals(other)

        override fun hashCode(): Int = super.hashCode() + mutation
    }
}
