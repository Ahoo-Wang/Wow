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

package me.ahoo.wow.api.query.annotation

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** The query field annotations are read at build time from the compiled domain, so they keep runtime retention. */
class QueryAnnotationsTest {
    @Test
    fun `semantic annotations are retained on fields`() {
        field("buyer").getAnnotation(QueryAlias::class.java).value.toList().assert().containsExactly("state.customer")
        field("ratio").getAnnotation(QueryDecimal::class.java).scale.assert().isEqualTo(4)
        field("amount").getAnnotation(QueryMoney::class.java).let {
            it.currency.assert().isEqualTo("CNY")
            it.currencyField.assert().isEmpty()
            it.scale.assert().isEqualTo(-1)
        }
        field("timeout").getAnnotation(QueryDuration::class.java).unit.assert().isEqualTo(TimeUnit.SECONDS)
        field("memberId").getAnnotation(QueryReference::class.java).let {
            it.aggregateName.assert().isEqualTo("member")
            it.contextName.assert().isEmpty()
        }
    }

    @Test
    fun `query index is retained on fields`() {
        field("status").getAnnotation(QueryIndex::class.java).let {
            it.name.assert().isEmpty()
            it.with.toList().assert().containsExactly("state.createdAt", "state.amount")
        }
        field("buyer").getAnnotation(QueryIndex::class.java).let {
            it.name.assert().isEqualTo("idx_order_buyer")
            it.with.toList().assert().isEmpty()
        }
    }

    @Test
    fun `sensitive annotation is retained on a value type`() {
        val sensitive = PhoneNumber::class.java.getAnnotation(Sensitive::class.java)
        sensitive.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
        sensitive.mask.assert().isEqualTo(Mask(keepPrefix = 3, keepSuffix = 4))
    }

    private fun field(name: String) = Fixture::class.java.getDeclaredField(name)

    @JvmInline
    @Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 4))
    private value class PhoneNumber(val value: String)

    private data class Fixture(
        @field:QueryAlias("state.customer")
        @field:QueryIndex(name = "idx_order_buyer")
        val buyer: String,
        @field:QueryDecimal(scale = 4)
        val ratio: Double,
        @field:QueryMoney(currency = "CNY")
        val amount: Long,
        @field:QueryDuration(TimeUnit.SECONDS)
        val timeout: Int,
        @field:QueryReference("member")
        val memberId: String,
        @field:QueryIndex(with = ["state.createdAt", "state.amount"])
        val status: String,
    )
}
