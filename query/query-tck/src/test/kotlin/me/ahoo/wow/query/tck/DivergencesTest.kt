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

package me.ahoo.wow.query.tck

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException

class DivergencesTest {
    @Test
    fun `a declared case that meets the standard fails, naming it`() {
        var ran = false
        assertThrows<AssertionError> {
            standard(ID, setOf(ID), BUCKET_RULE) { ran = true }
        }.message.assert().isEqualTo(
            "[$ID] is declared as a divergence, but it meets the standard: remove it from the list.",
        )
        ran.assert().isTrue()
    }

    @Test
    fun `a declared case that still diverges is reported skipped as before`() {
        assertThrows<TestAbortedException> {
            standard(ID, setOf(ID), CANONICAL_SEMANTICS) { throw AssertionError("expected [a] but was [b]") }
        }.message.assert().isEqualTo("Known divergence from the canonical semantics: $ID.")
        // A backend that refuses the case, rather than answering it otherwise, diverges too.
        assertThrows<TestAbortedException> {
            standard(ID, setOf(ID), BUCKET_RULE) { throw UnsupportedOperationException("not supported") }
        }.message.assert().isEqualTo("Known divergence from the bucket rule: $ID.")
    }

    @Test
    fun `a declared case that skips itself keeps its own reason`() {
        assertThrows<TestAbortedException> {
            standard(ID, setOf(ID), BUCKET_RULE) { Assumptions.abort<Unit>("the storage declines it") }
        }.message.assert().isEqualTo("the storage declines it")
    }

    @Test
    fun `a case not declared runs, and its failure is the test's`() {
        var ran = false
        standard(ID, setOf("other"), BUCKET_RULE) { ran = true }
        ran.assert().isTrue()
        assertThrows<AssertionError> {
            standard(ID, emptySet(), BUCKET_RULE) { throw AssertionError("expected [a] but was [b]") }
        }.message.assert().isEqualTo("expected [a] but was [b]")
    }

    private companion object {
        const val ID = "date-histogram.gap-start"
    }
}
