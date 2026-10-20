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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * Both versions' specs run the same table: this module's TransferCases.kt is the 9.x module's, byte for byte. The 9.x
 * module left `v10` (decision D262) and runs from the side branch `v10-9x-baseline`, where its
 * `wow-benchmarks/e2e/bench-9x/src/test/kotlin/me/ahoo/wow/benchmark/transfer/TransferCases.kt` stays as it is, so the
 * check compares against that file's SHA-256. Changing the table here means changing it there too.
 */
class SharedCasesTest {
    @Test
    fun `the table of cases should be the 9x module's`() {
        val v10 = File(System.getProperty("wow.benchmark.v10-cases"))
        val digest = MessageDigest.getInstance("SHA-256").digest(v10.readBytes()).joinToString("") { "%02x".format(it) }
        digest.assert().isEqualTo(NINE_X_CASES_SHA256)
    }

    private companion object {
        const val NINE_X_CASES_SHA256 = "7c9e8b6fd7762693690d1db2c6e1de840fba535613f39b72c2fb92150509475c"
    }
}
