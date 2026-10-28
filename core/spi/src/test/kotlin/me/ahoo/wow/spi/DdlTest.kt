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

package me.ahoo.wow.spi

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class DdlTest {
    private fun run(ddl: Ddl, exists: Boolean): List<String> {
        val ran = mutableListOf<String>()
        ddl.apply("The table t", exists = { exists }, create = { ran += "create" }) { ran += "validate" }
        return ran
    }

    @Test
    fun `auto should create what is missing and validate what exists`() {
        run(Ddl.AUTO, exists = false).assert().containsExactly("create")
        run(Ddl.AUTO, exists = true).assert().containsExactly("validate")
        Ddl.AUTO.creates.assert().isTrue()
        Ddl.AUTO.checks.assert().isTrue()
    }

    @Test
    fun `validate should refuse what is missing, naming it, and validate what exists`() {
        assertThrownBy<IllegalStateException> { run(Ddl.VALIDATE, exists = false) }
            .hasMessage("The table t does not exist, and the store only validates its schema (ddl validate).")
        run(Ddl.VALIDATE, exists = true).assert().containsExactly("validate")
        Ddl.VALIDATE.creates.assert().isFalse()
        Ddl.VALIDATE.checks.assert().isTrue()
    }

    @Test
    fun `none should do nothing`() {
        run(Ddl.NONE, exists = false).assert().isEmpty()
        Ddl.NONE.checks.assert().isFalse()
    }

    private fun ensure(ddl: Ddl, exists: Boolean): List<String> {
        val ran = mutableListOf<String>()
        var created = exists
        ddl.ensure("The table t", create = {
            ran += "create"
            created = true
        }) {
            ran += "validate"
            created
        }
        return ran
    }

    @Test
    fun `ensure under auto should create idempotently, then validate, without asking whether it exists`() {
        ensure(Ddl.AUTO, exists = false).assert().containsExactly("create", "validate")
        ensure(Ddl.AUTO, exists = true).assert().containsExactly("create", "validate")
        assertThrownBy<IllegalStateException> { Ddl.AUTO.ensure("The table t", create = {}) { false } }
            .hasMessage("The table t does not exist after its creation.")
    }

    @Test
    fun `ensure under validate should validate alone and refuse what is missing, naming it`() {
        ensure(Ddl.VALIDATE, exists = true).assert().containsExactly("validate")
        assertThrownBy<IllegalStateException> { ensure(Ddl.VALIDATE, exists = false) }
            .hasMessage(
                "The table t does not exist, and the store only validates its schema (ddl validate): create it " +
                    "first, with ddl auto once or the migration tool.",
            )
    }

    @Test
    fun `ensure under none should do nothing`() {
        ensure(Ddl.NONE, exists = false).assert().isEmpty()
    }
}
