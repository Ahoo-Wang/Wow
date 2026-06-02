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

import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

internal class DefaultHeaderTest {
    @Test
    fun `should convert map to header`() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.assert().isNotNull()
    }

    @Test
    fun `should copy mutable map when converting to header`() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE

        val header = values.toHeader()
        values[KEY] = "changed"

        header[KEY].assert().isEqualTo(VALUE)
    }

    @Test
    fun `should use supplied mutable map when constructing header`() {
        val values = mutableMapOf(KEY to VALUE)

        val header = DefaultHeader(values)
        values[KEY] = "changed"

        header[KEY].assert().isEqualTo("changed")
    }

    @Test
    fun `should be empty when header has no entries`() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.assert().isEmpty()
    }

    @Test
    fun `should report isEmpty correctly`() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.isEmpty().assert().isEqualTo(true)
    }

    @Test
    fun `should check containsKey`() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.toHeader()
        header.size.assert().isEqualTo(1)
        header.isEmpty().assert().isFalse()
        header.containsKey(KEY).assert().isEqualTo(true)
        header.containsKey(MockIdGenerator.INSTANCE.generateAsString()).assert().isFalse()
    }

    @Test
    fun `should check containsValue`() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.toHeader()
        header.size.assert().isEqualTo(1)
        header.isEmpty().assert().isFalse()
        header.containsValue(VALUE).assert().isEqualTo(true)
        header.containsValue(MockIdGenerator.INSTANCE.generateAsString()).assert().isFalse()
    }

    @Test
    fun `should get value by key`() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.toHeader()
        header.size.assert().isEqualTo(1)
        header.isEmpty().assert().isFalse()
        header[KEY].assert().isEqualTo(VALUE)
    }

    @Test
    fun `should return empty key set`() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.keys.assert().isEmpty()
    }

    @Test
    fun `should return empty values`() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.values.assert().isEmpty()
    }

    @Test
    fun `should return empty entry set`() {
        DefaultHeader.empty().entries.assert().isEmpty()
    }

    @Test
    fun `should implement equals correctly`() {
        DefaultHeader.empty().assert().isEqualTo(DefaultHeader.empty())
        DefaultHeader.empty().assert().isNotEqualTo(Any())
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = DefaultHeader(values)
        var otherHeader = DefaultHeader(HashMap())
        header.assert().isNotEqualTo(otherHeader)
        otherHeader = DefaultHeader(values)
        header.assert().isEqualTo(otherHeader)
    }

    @Test
    fun `should implement map equals contract`() {
        val header = DefaultHeader(mutableMapOf(KEY to VALUE))

        header.assert().isEqualTo(mapOf(KEY to VALUE))
    }

    @Test
    fun `should return zero hashCode for empty header`() {
        DefaultHeader.empty().hashCode().assert().isEqualTo(0)
    }

    @Test
    fun `should merge headers with additional source`() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = DefaultHeader(values)
        val additionalKey = "additionalKey"
        val additionalValue = "additionalValue"
        val additionalSource = HashMap<String, String>()
        additionalSource[additionalKey] = additionalValue
        val mergedHeader = header.with(additionalSource)
        mergedHeader[KEY].assert().isEqualTo(VALUE)
        mergedHeader[additionalKey].assert().isEqualTo(additionalValue)
    }

    @Test
    fun `should return same empty when merging with empty`() {
        val mergedHeader = DefaultHeader.empty().with(HashMap())
        mergedHeader.assert().isEqualTo(DefaultHeader.empty())
    }

    @Test
    fun `should merge when this header is empty`() {
        val additionalSource = HashMap<String, String>()
        additionalSource[KEY] = VALUE
        val mergedHeader = DefaultHeader.empty().with(additionalSource)
        mergedHeader.assert().isNotEqualTo(DefaultHeader.empty())
        mergedHeader[KEY].assert().isEqualTo(VALUE)
    }

    @Test
    fun `should create copy that is not same instance`() {
        val sourceHeader = DefaultHeader.empty()
        sourceHeader.assert().isNotSameAs(sourceHeader.copy())
    }

    @Test
    fun `should create non-readonly empty header`() {
        val header = DefaultHeader.empty()
        header.isReadOnly.assert().isEqualTo(false)
    }

    @Test
    fun `should throw UnsupportedOperationException on readonly header operations`() {
        val header = DefaultHeader.empty().withReadOnly()
        header.isReadOnly.assert().isEqualTo(true)
        assertThrownBy<UnsupportedOperationException> {
            header[KEY] = VALUE
        }
        assertThrownBy<UnsupportedOperationException> {
            header.remove(KEY)
        }
        assertThrownBy<UnsupportedOperationException> {
            header.remove(KEY, VALUE)
        }
        assertThrownBy<UnsupportedOperationException> {
            header.putAll(mapOf(KEY to VALUE))
        }
    }

    @Test
    fun `should throw UnsupportedOperationException on readonly header view operations`() {
        val header = DefaultHeader(mutableMapOf(KEY to VALUE)).withReadOnly()

        assertThrownBy<UnsupportedOperationException> {
            header.keys.remove(KEY)
        }
        assertThrownBy<UnsupportedOperationException> {
            header.values.remove(VALUE)
        }
        assertThrownBy<UnsupportedOperationException> {
            header.entries.clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            header.entries.first().setValue("changed")
        }
        header[KEY].assert().isEqualTo(VALUE)
    }

    @Test
    fun `should throw UnsupportedOperationException on captured header view operations after readonly`() {
        val header = DefaultHeader(mutableMapOf(KEY to VALUE))
        val keys = header.keys
        val values = header.values
        val entries = header.entries
        val entry = entries.first()

        header.withReadOnly()

        assertThrownBy<UnsupportedOperationException> {
            keys.remove(KEY)
        }
        assertThrownBy<UnsupportedOperationException> {
            values.remove(VALUE)
        }
        assertThrownBy<UnsupportedOperationException> {
            entries.clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            entry.setValue("changed")
        }
        header[KEY].assert().isEqualTo(VALUE)
    }

    companion object {
        private const val KEY = "key"
        private const val VALUE = "value"
    }
}
