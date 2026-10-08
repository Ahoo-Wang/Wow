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

package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class DefaultHeaderTest {

    @Test
    fun `empty creates mutable empty header`() {
        val header = DefaultHeader.empty()

        header.assert().isEmpty()
        header.isReadOnly.assert().isFalse()

        header.with("key", "value")

        header["key"].assert().isEqualTo("value")
    }

    @Test
    fun `write operations mutate the backing entries until read only`() {
        val header = DefaultHeader(mutableMapOf("one" to "1"))

        header.put("two", "2").assert().isNull()
        header.put("two", "22").assert().isEqualTo("2")
        header.remove("missing").assert().isNull()
        header.remove("two", "2").assert().isFalse()
        header.remove("two", "22").assert().isTrue()
        header.putAll(mapOf("three" to "3", "four" to "4"))

        header["one"].assert().isEqualTo("1")
        header["three"].assert().isEqualTo("3")
        header["four"].assert().isEqualTo("4")

        header.clear()

        header.assert().isEmpty()
    }

    @Test
    fun `read only header rejects all mutation paths`() {
        val header = DefaultHeader(mutableMapOf("key" to "value")).withReadOnly()

        header.isReadOnly.assert().isTrue()
        assertThrownBy<UnsupportedOperationException> { header["other"] = "value" }
        assertThrownBy<UnsupportedOperationException> { header.with("other", "value") }
        assertThrownBy<UnsupportedOperationException> { header.with(mapOf("other" to "value")) }
        assertThrownBy<UnsupportedOperationException> { header.remove("key") }
        assertThrownBy<UnsupportedOperationException> { header.remove("key", "value") }
        assertThrownBy<UnsupportedOperationException> { header.clear() }
    }

    @Test
    fun `copy is independent and mutable even when source is read only`() {
        val source = DefaultHeader(mutableMapOf("key" to "value")).withReadOnly()

        val copy = source.copy()
        copy.isReadOnly.assert().isFalse()
        copy["key"].assert().isEqualTo("value")

        copy.with("key", "changed")
        copy.with("copy-only", "true")

        source["key"].assert().isEqualTo("value")
        source.containsKey("copy-only").assert().isFalse()
    }

    @Test
    fun `copy shares the entries until either side writes`() {
        val source = DefaultHeader().with("key", "value") as DefaultHeader

        val copy = source.copy() as DefaultHeader

        copy.sharesEntriesWith(source).assert().isTrue()
        copy.assert().isEqualTo(source)
        copy.hashCode().assert().isEqualTo(source.hashCode())

        source.with("source-only", "true")

        copy.sharesEntriesWith(source).assert().isFalse()
        copy.containsKey("source-only").assert().isFalse()
        copy["key"].assert().isEqualTo("value")
    }

    @Test
    fun `a write to the copy never reaches the source or a sibling copy`() {
        val source = DefaultHeader().with("key", "value") as DefaultHeader
        val first = source.copy()
        val second = source.copy()
        val ofCopy = first.copy()

        first.with("key", "first")
        second.remove("key")
        ofCopy.putAll(mapOf("of-copy" to "true"))

        source.toMap().assert().isEqualTo(mapOf("key" to "value"))
        first.toMap().assert().isEqualTo(mapOf("key" to "first"))
        second.assert().isEmpty()
        ofCopy.toMap().assert().isEqualTo(mapOf("key" to "value", "of-copy" to "true"))
    }

    @Test
    fun `clear and conditional remove on a copy take private entries`() {
        val source = DefaultHeader().with("key", "value")
        val cleared = source.copy()
        val removed = source.copy()

        cleared.clear()
        removed.remove("key", "value").assert().isTrue()

        source["key"].assert().isEqualTo("value")
        cleared.assert().isEmpty()
        removed.assert().isEmpty()
    }

    @Test
    fun `entries of a header built around a caller map are copied at once`() {
        val callerMap = mutableMapOf("key" to "value")
        val header = DefaultHeader(callerMap)

        val copy = header.copy() as DefaultHeader
        callerMap["key"] = "changed"

        copy.sharesEntriesWith(header).assert().isFalse()
        copy["key"].assert().isEqualTo("value")
        header["key"].assert().isEqualTo("changed")
    }

    @Test
    fun `copy of an empty header shares nothing`() {
        val source = DefaultHeader()

        val copy = source.copy() as DefaultHeader
        copy.with("key", "value")

        copy.sharesEntriesWith(source).assert().isFalse()
        source.assert().isEmpty()
    }

    @Test
    fun `view changes on a copy never reach the source`() {
        val source = DefaultHeader().with("one", "1").with("two", "2").with("three", "3")
        val copy = source.copy()

        copy.keys.remove("one").assert().isTrue()
        copy.keys.remove("missing").assert().isFalse()
        copy.values.remove("2").assert().isTrue()
        copy.entries.first().setValue("33").assert().isEqualTo("3")

        source.toMap().assert().isEqualTo(mapOf("one" to "1", "two" to "2", "three" to "3"))
        copy.toMap().assert().isEqualTo(mapOf("three" to "33"))
    }

    @Test
    fun `iterator removal on a shared header removes from its private entries`() {
        val source = DefaultHeader().with("one", "1").with("two", "2")
        val copy = source.copy()

        val iterator = copy.entries.iterator()
        iterator.next().key.assert().isEqualTo("one")
        iterator.remove()
        iterator.next().key.assert().isEqualTo("two")
        iterator.hasNext().assert().isFalse()

        copy.toMap().assert().isEqualTo(mapOf("two" to "2"))
        source.toMap().assert().isEqualTo(mapOf("one" to "1", "two" to "2"))

        iterator.remove()
        copy.assert().isEmpty()
        assertThrownBy<IllegalStateException> { iterator.remove() }
    }

    @Test
    fun `a view taken before a copy does not change the copy`() {
        val source = DefaultHeader().with("one", "1").with("two", "2")
        val keys = source.keys
        val entries = source.entries.iterator()
        val firstEntry = entries.next()

        val copy = source.copy()
        keys.remove("two")
        firstEntry.setValue("11").assert().isEqualTo("1")
        firstEntry.value.assert().isEqualTo("11")

        copy.toMap().assert().isEqualTo(mapOf("one" to "1", "two" to "2"))
        source.toMap().assert().isEqualTo(mapOf("one" to "11"))
    }

    @Test
    fun `views of an unshared header change it in place`() {
        val header = DefaultHeader().with("one", "1").with("two", "2").with("three", "3")

        val iterator = header.keys.iterator()
        iterator.next()
        iterator.remove()
        header.entries.first().setValue("22")
        header.entries.remove(header.entries.last()).assert().isTrue()
        header.entries.contains(header.entries.first()).assert().isTrue()

        header.toMap().assert().isEqualTo(mapOf("two" to "22"))
        header.values.contains("22").assert().isTrue()
        header.keys.contains("two").assert().isTrue()
        header.values.size.assert().isEqualTo(1)
        header.entries.first().toString().assert().isEqualTo("two=22")
        header.entries.first().hashCode().assert().isEqualTo("two".hashCode() xor "22".hashCode())
        header.entries.first().assert().isEqualTo(java.util.AbstractMap.SimpleEntry("two", "22"))
        header.entries.first().equals("two").assert().isFalse()
    }

    @Test
    fun `an entry of a header that took private entries reads them`() {
        val header = DefaultHeader().with("one", "1").with("two", "2")
        val iterator = header.entries.iterator()
        val first = iterator.next()

        header.copy()
        header.with("one", "11")
        first.value.assert().isEqualTo("11")
        header.remove("one")
        first.value.assert().isEqualTo("1")

        header.containsValue("2").assert().isTrue()
        header.keys.size.assert().isEqualTo(1)
        header.entries.size.assert().isEqualTo(1)
        first.equals(java.util.AbstractMap.SimpleEntry("other", "1")).assert().isFalse()
    }

    @Test
    fun `clearing a view of a copy clears only the copy`() {
        val source = DefaultHeader().with("key", "value")
        val byKeys = source.copy()
        val byValues = source.copy()
        val byEntries = source.copy()

        byKeys.keys.clear()
        byValues.values.clear()
        byEntries.entries.clear()
        byEntries.entries.remove(java.util.AbstractMap.SimpleEntry("key", "value")).assert().isFalse()

        byKeys.assert().isEmpty()
        byValues.assert().isEmpty()
        byEntries.assert().isEmpty()
        source["key"].assert().isEqualTo("value")
    }

    @Test
    fun `views do not support adding`() {
        val header = DefaultHeader().with("key", "value")

        assertThrownBy<UnsupportedOperationException> { header.keys.add("other") }
        assertThrownBy<UnsupportedOperationException> { header.values.add("other") }
        assertThrownBy<UnsupportedOperationException> {
            header.entries.add(java.util.AbstractMap.SimpleEntry("other", "value"))
        }
    }

    @Test
    fun `read only state and toString`() {
        val header = DefaultHeader(mutableMapOf("key" to "value"), isReadOnly = true)

        header.isReadOnly.assert().isTrue()
        header.toString().assert().isEqualTo("DefaultHeader(delegate={key=value})")
        header.equals("key").assert().isFalse()
        header.assert().isEqualTo(header)
    }

    @Test
    fun `toHeader returns empty header for null and empty maps`() {
        val nullMap: Map<String, String>? = null

        nullMap.toHeader().assert().isEmpty()
        emptyMap<String, String>().toHeader().assert().isEmpty()
    }

    @Test
    fun `toHeader keeps header instances and copies plain maps`() {
        val header = DefaultHeader.empty().with("same", "instance")
        val sameHeader = header.toHeader()
        val source = mutableMapOf("key" to "value")
        val copied = source.toHeader()

        sameHeader.assert().isSameAs(header)
        copied.assert().isNotSameAs(source)
        copied["key"].assert().isEqualTo("value")

        source["key"] = "changed"

        copied["key"].assert().isEqualTo("value")
    }
}
