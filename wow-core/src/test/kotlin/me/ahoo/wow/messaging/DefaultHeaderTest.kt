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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class DefaultHeaderTest {
    @Test
    fun asHeader() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.assert().isNotNull()
    }

    @Test
    fun size() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.assert().isEmpty()
    }

    @Test
    fun isEmpty() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.isEmpty().assert().isEqualTo(true)
    }

    @Test
    fun containsKey() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.toHeader()
        header.size.assert().isEqualTo(1)
        header.isEmpty().assert().isFalse()
        header.containsKey(KEY).assert().isEqualTo(true)
        header.containsKey(MockIdGenerator.INSTANCE.generateAsString()).assert().isFalse()
    }

    @Test
    fun containsValue() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.toHeader()
        header.size.assert().isEqualTo(1)
        header.isEmpty().assert().isFalse()
        header.containsValue(VALUE).assert().isEqualTo(true)
        header.containsValue(MockIdGenerator.INSTANCE.generateAsString()).assert().isFalse()
    }

    @Test
    fun get() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.toHeader()
        header.size.assert().isEqualTo(1)
        header.isEmpty().assert().isTrue()
        header[KEY].assert().isEqualTo(VALUE)
    }

    @Test
    fun keySet() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.keys.assert().isEmpty()
    }

    @Test
    fun values() {
        val values = HashMap<String, String>()
        val header = values.toHeader()
        header.values.assert().isEmpty()
    }

    @Test
    fun entrySet() {
        DefaultHeader.empty().entries.assert().isEmpty()
    }

    @Test
    fun testEquals() {
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
    fun testHashCode() {
        DefaultHeader.empty().hashCode().assert().isEqualTo(0)
    }

    @Test
    fun mergeWith() {
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
    fun mergeWithWhenEmpty() {
        val mergedHeader = DefaultHeader.empty().with(HashMap())
        mergedHeader.assert().isEqualTo(DefaultHeader.empty())
    }

    @Test
    fun mergeWithWhenThisIsEmpty() {
        val additionalSource = HashMap<String, String>()
        additionalSource[KEY] = VALUE
        val mergedHeader = DefaultHeader.empty().with(additionalSource)
        mergedHeader.assert().isNotEqualTo(DefaultHeader.empty())
        mergedHeader[KEY].assert().isEqualTo(VALUE)
    }

    @Test
    fun createWhenCopyHeader() {
        val sourceHeader = DefaultHeader.empty()
        Assertions.assertNotSame(sourceHeader, sourceHeader.copy())
    }

    @Test
    fun empty() {
        val header = DefaultHeader.empty()
        header.isReadOnly.assert().isEqualTo(false)
    }

    @Test
    fun withReadonly() {
        val header = DefaultHeader.empty().withReadOnly()
        header.isReadOnly.assert().isEqualTo(true)
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            header[KEY] = VALUE
        }
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            header.remove(KEY)
        }
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            header.remove(KEY, VALUE)
        }
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            header.putAll(mapOf(KEY to VALUE))
        }
    }

    companion object {
        private const val KEY = "key"
        private const val VALUE = "value"
    }
}
