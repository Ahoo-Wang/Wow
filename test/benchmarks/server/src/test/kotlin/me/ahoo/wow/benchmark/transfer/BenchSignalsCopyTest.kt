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
 * The eShop soak's nodes run a copy of [BenchSignals] (SoakSignals in `:eshop-e2e`'s soakTest source set, D224 note
 * ⑦), until a shared module takes both in 10.x. The two classes are the same but for their names, read from the
 * source tree (the build passes both paths), so they count the same signals and log the same `bench-signals {json}`.
 */
class BenchSignalsCopyTest {
    private fun classOf(file: File, name: String): String {
        val text = file.readText()
        val start = text.indexOf("class $name : Observer")
        check(start >= 0) { "No class $name in $file." }
        return text.substring(start, text.indexOf("\n}\n", start) + 2).replace(name, "Signals")
    }

    @Test
    fun `the soak's copy should be the benchmark's signals`() {
        val bench = File(System.getProperty("wow-test.benchmark.bench-signals"))
        val soak = File(System.getProperty("wow-test.benchmark.soak-signals"))
        classOf(soak, "SoakSignals").assert().isEqualTo(classOf(bench, "BenchSignals"))
    }
}
