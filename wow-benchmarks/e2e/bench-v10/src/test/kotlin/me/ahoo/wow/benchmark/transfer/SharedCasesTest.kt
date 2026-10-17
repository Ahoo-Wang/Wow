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

/**
 * Both versions' specs run the same table: this module's TransferCases.kt is the 9.x module's, byte for byte, read
 * from the source tree (the build passes both paths), so the check holds in the V10 build scope too.
 */
class SharedCasesTest {
    @Test
    fun `the table of cases should be the 9x module's`() {
        val nineX = File(System.getProperty("wow.benchmark.nine-x-cases"))
        val v10 = File(System.getProperty("wow.benchmark.v10-cases"))
        v10.readText().assert().isEqualTo(nineX.readText())
    }
}
