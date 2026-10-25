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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The measured sizes of the states of each class (decision D274). */
class StateSizesTest {
    @Test
    fun `a class not measured yet weighs the fixed estimate`() {
        val cached = CachedState(CartState("c-1"), 1)
        StateSizes().bytesOf(cached).assert().isEqualTo(cached.bytes)
    }

    @Test
    fun `a measured class weighs its entry and twice its mean JSON`() {
        val sizes = StateSizes()
        sizes.record(CartState::class.java, 100)
        sizes.record(CartState::class.java, 200)
        sizes.meanLength(CartState::class.java).assert().isEqualTo(150)
        sizes.bytesOf(CachedState(CartState("c-1"), 1))
            .assert().isEqualTo(StateSizes.ENTRY_BYTES + StateSizes.TEXT_FACTOR * 150)
    }

    @Test
    fun `the mean follows about the latest window of states`() {
        val sizes = StateSizes()
        repeat(StateSizes.WINDOW) { sizes.record(CartState::class.java, 1_000) }
        repeat(4 * StateSizes.WINDOW) { sizes.record(CartState::class.java, 100) }
        sizes.meanLength(CartState::class.java)!!.assert().isLessThan(120)
    }

    @Test
    fun `a codec measures the states it writes and reads`() {
        val codec = EventCodec()
        val state = CartState("c-1").apply { items["apple"] = 2 }
        val text = codec.stateText(state)
        codec.sizes.meanLength(CartState::class.java).assert().isEqualTo(text.length.toLong())
        codec.decodeState(cartModel, text)
        codec.sizes.meanLength(CartState::class.java).assert().isEqualTo(text.length.toLong())
    }
}
