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

package me.ahoo.wow.query.internal.normalization

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class NormalizedValueTest {

    @Test
    fun `bytes should have content equality and defensive access`() {
        val source = byteArrayOf(1, 2, 3)
        val value = NormalizedValue.Bytes(source)
        val expected = NormalizedValue.Bytes(byteArrayOf(1, 2, 3))
        val initialHashCode = value.hashCode()

        source[0] = 9
        val exposed = value.toByteArray()
        exposed[1] = 9

        value.assert().isEqualTo(expected)
        value.hashCode().assert().isEqualTo(initialHashCode)
        value.toByteArray().assert().isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `list and object should deeply isolate mutable inputs`() {
        val sourceBytes = byteArrayOf(1, 2)
        val sourceList = mutableListOf<NormalizedValue>(NormalizedValue.Bytes(sourceBytes))
        val sourceMap = linkedMapOf<String, NormalizedValue>("items" to NormalizedValue.ListValue(sourceList))
        val value = NormalizedValue.ObjectValue(sourceMap)
        val initialHashCode = value.hashCode()

        sourceBytes[0] = 9
        sourceList.add(NormalizedValue.Text("late"))
        sourceMap.clear()

        value.assert().isEqualTo(
            NormalizedValue.ObjectValue(
                mapOf("items" to NormalizedValue.ListValue(listOf(NormalizedValue.Bytes(byteArrayOf(1, 2))))),
            ),
        )
        value.hashCode().assert().isEqualTo(initialHashCode)
    }

    @Test
    fun `immutable collections should not be mutable through a cast`() {
        val list = NormalizedValue.ListValue(listOf(NormalizedValue.Text("value")))
        val map = NormalizedValue.ObjectValue(mapOf("key" to NormalizedValue.Text("value")))

        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (list.values as MutableList<NormalizedValue>).add(NormalizedValue.Null)
        }
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (map.values as MutableMap<String, NormalizedValue>)["other"] = NormalizedValue.Null
        }
    }

    @Test
    fun `object equality should preserve Mongo document field order`() {
        val first = NormalizedValue.ObjectValue(
            linkedMapOf("a" to NormalizedValue.Int64(1), "b" to NormalizedValue.Int64(2)),
        )
        val reversed = NormalizedValue.ObjectValue(
            linkedMapOf("b" to NormalizedValue.Int64(2), "a" to NormalizedValue.Int64(1)),
        )

        first.assert().isNotEqualTo(reversed)
        first.hashCode().assert().isNotEqualTo(reversed.hashCode())
    }

    @Test
    fun `list should materialize a one-shot iterable exactly once`() {
        var iteratorCalls = 0
        val oneShot = Iterable {
            iteratorCalls++
            check(iteratorCalls == 1)
            listOf(NormalizedValue.Text("value")).iterator()
        }

        val value = NormalizedValue.ListValue(oneShot)

        value.values.assert().containsExactly(NormalizedValue.Text("value"))
        iteratorCalls.assert().isEqualTo(1)
    }
}
