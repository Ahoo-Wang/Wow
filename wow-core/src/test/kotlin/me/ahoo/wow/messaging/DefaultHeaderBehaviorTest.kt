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
import me.ahoo.wow.api.messaging.Header
import org.junit.jupiter.api.Test

class DefaultHeaderBehaviorTest {

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
    fun `empty and copy use compact small map backing`() {
        val source = DefaultHeader.empty()
            .with("one", "1")
            .with("two", "2")

        val copy = source.copy()

        source.backingMap().javaClass.simpleName.assert().isEqualTo("SmallHeaderMap")
        copy.backingMap().javaClass.simpleName.assert().isEqualTo("SmallHeaderMap")
        copy.assert().isEqualTo(source)
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

    @Suppress("UNCHECKED_CAST")
    private fun Header.backingMap(): MutableMap<String, String> {
        val field = DefaultHeader::class.java.getDeclaredField("delegate")
        field.isAccessible = true
        return field.get(this) as MutableMap<String, String>
    }
}
