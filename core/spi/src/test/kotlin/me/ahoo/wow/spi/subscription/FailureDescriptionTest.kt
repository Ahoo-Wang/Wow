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

package me.ahoo.wow.spi.subscription

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.code
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.name
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.path
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.phrase
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.type
import me.ahoo.wow.spi.subscription.FailureDescription.PathSegment
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** #6588: a failure's description holds no text a failure or a value gave: phrases, names, paths, types and codes. */
class FailureDescriptionTest {
    enum class Words { CANNOT_READ, AS, WRITE_ERROR }

    @Test
    fun `a description should write its parts in order, each as its kind is written`() {
        val segments = listOf(
            PathSegment.property("items"),
            PathSegment.index(0),
            PathSegment.property("prices"),
            PathSegment.KEY,
            PathSegment.property("amount"),
        )
        FailureDescription.of(phrase(Words.CANNOT_READ), path(segments), phrase(Words.AS), type(BigDecimal::class.java))
            .text.assert().isEqualTo("cannot read 'items[0].prices.{key}.amount' as BigDecimal")
        FailureDescription.of(phrase(Words.WRITE_ERROR), code(11000), name("email_1")).toString().assert()
            .isEqualTo("write error 11000 'email_1'")
        FailureDescription.of(type(IntArray::class.java)).text.assert().isEqualTo("int[]")
        FailureDescription.of(code(1)).assert().isEqualTo(FailureDescription.of(code(1)))
        FailureDescription.of(code(1)).hashCode().assert().isEqualTo("1".hashCode())
    }

    @Test
    fun `a name should take what names a property, an index or a code, and no free text or value`() {
        listOf("email_1", "readers_email_key", "23505", "42P01", "\$type", "a.b-c").forEach {
            name(it).text.assert().isEqualTo("'$it'")
        }
        listOf("", "alice smith", "alice.smith@example.com", "4111 1111 1111 1111", "a'b", "x".repeat(65), "a/b")
            .forEach { value ->
                assertThrownBy<IllegalArgumentException> { name(value) }
                assertThrownBy<IllegalArgumentException> { PathSegment.property(value) }
            }
        assertThrownBy<IllegalArgumentException> { PathSegment.index(-1) }
        assertThrownBy<IllegalArgumentException> { path(emptyList()) }
        assertThrownBy<IllegalArgumentException> { FailureDescription.of() }
    }

    @Test
    fun `none of the describers by default describes anything`() {
        FailureDescriber.NONE.describe(IllegalStateException("x")).assert().isNull()
    }
}
