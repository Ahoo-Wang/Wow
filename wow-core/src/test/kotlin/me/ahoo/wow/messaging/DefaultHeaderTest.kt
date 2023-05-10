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
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.aMapWithSize
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

internal class DefaultHeaderTest {
    @Test
    fun of() {
        val values = HashMap<String, String>()
        val header = values.asHeader()
        assertThat(header, notNullValue())
    }

    @Test
    fun size() {
        val values = HashMap<String, String>()
        val header = values.asHeader()
        assertThat(header.size, equalTo(0))
    }

    @get:Test
    val isEmpty: Unit
        get() {
            val values = HashMap<String, String>()
            val header = values.asHeader()
            assertThat(header.isEmpty(), equalTo(true))
        }

    @Test
    fun containsKey() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.asHeader()
        assertThat(header.size, equalTo(1))
        assertThat(header.isEmpty(), not(true))
        assertThat(header.containsKey(KEY), equalTo(true))
        assertThat(
            header.containsKey(MockIdGenerator.INSTANCE.generateAsString()),
            equalTo(false),
        )
    }

    @Test
    fun containsValue() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.asHeader()
        assertThat(header.size, equalTo(1))
        assertThat(header.isEmpty(), not(true))
        assertThat(header.containsValue(VALUE), equalTo(true))
        assertThat(
            header.containsValue(MockIdGenerator.INSTANCE.generateAsString()),
            equalTo(false),
        )
    }

    @Test
    fun get() {
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = values.asHeader()
        assertThat(header.size, equalTo(1))
        assertThat(header.isEmpty(), not(true))
        assertThat(header[KEY], equalTo(VALUE))
    }

    @Test
    fun keySet() {
        val values = HashMap<String, String>()
        val header = values.asHeader()
        assertThat(header.keys, equalTo(emptySet<Any>()))
    }

    @Test
    fun values() {
        val values = HashMap<String, String>()
        val header = values.asHeader()
        assertThat(header.values.isEmpty(), equalTo(values.values.isEmpty()))
    }

    @Test
    fun entrySet() {
        assertThat(DefaultHeader.EMPTY, aMapWithSize(0))
    }

    @Test
    fun testEquals() {
        assertThat(DefaultHeader.EMPTY, equalTo(DefaultHeader.EMPTY))
        assertThat(DefaultHeader.EMPTY, not(Any()))
        val values = HashMap<String, String>()
        values[KEY] = VALUE
        val header = DefaultHeader(values)
        var otherHeader = DefaultHeader(HashMap())
        assertThat(header, not(otherHeader))
        otherHeader = DefaultHeader(values)
        assertThat(header, equalTo(otherHeader))
    }

    @Test
    fun testHashCode() {
        assertThat(DefaultHeader.EMPTY.hashCode(), equalTo(0))
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
        val mergedHeader = header.mergeWith(additionalSource)
        assertThat(mergedHeader[KEY], equalTo(VALUE))
        assertThat(mergedHeader[additionalKey], equalTo(additionalValue))
    }

    @Test
    fun mergeWithWhenEmpty() {
        val mergedHeader = DefaultHeader.EMPTY.mergeWith(HashMap())
        assertThat(mergedHeader, equalTo(DefaultHeader.EMPTY))
    }

    @Test
    fun mergeWithWhenThisIsEmpty() {
        val additionalSource = HashMap<String, String>()
        additionalSource[KEY] = VALUE
        val mergedHeader = DefaultHeader.EMPTY.mergeWith(additionalSource)
        assertThat(mergedHeader, not(DefaultHeader.EMPTY))
        assertThat(mergedHeader[KEY], equalTo(VALUE))
    }

    companion object {
        private const val KEY = "key"
        private const val VALUE = "value"
    }
}
