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

package me.ahoo.wow.example.quickstart

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The quickstart page, in English and Chinese, shows this module's sources as they are: each code block that follows
 * a `<!-- sample: <path> -->` comment is the file at that path of this module, without its license header. So a sample
 * on the page compiles and its tests pass, or this test fails.
 */
class QuickstartPageTest {
    private val module = File(System.getProperty("wow.quickstart.module"))
    private val pages = System.getProperty("wow.quickstart.pages").split(File.pathSeparator).map(::File)

    @TestFactory
    fun `every sample of the page should be the module's source`(): List<DynamicTest> = pages.map { page ->
        DynamicTest.dynamicTest(page.parentFile.parentFile.parentFile.name) {
            val samples = SAMPLE.findAll(page.readText()).associate { it.groupValues[1] to it.groupValues[2] }
            samples.keys.assert().describedAs("the samples of $page").containsExactlyInAnyOrderElementsOf(SOURCES)
            samples.forEach { (path, block) ->
                block.assert().describedAs("the sample of $path in $page").isEqualTo(source(path))
            }
        }
    }

    private fun source(path: String): String =
        File(module, path).readText().replace(LICENSE_HEADER, "").trimEnd()

    private companion object {
        val SAMPLE = Regex("""<!-- sample: (\S+) -->\n+```\w+\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val LICENSE_HEADER = Regex("""\A/\*.*?\*/\n+""", RegexOption.DOT_MATCHES_ALL)
        const val SOURCES_ROOT = "src/main/kotlin/me/ahoo/wow/example/quickstart"
        val SOURCES = listOf(
            "$SOURCES_ROOT/Bank.kt",
            "$SOURCES_ROOT/Account.kt",
            "src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt",
            "$SOURCES_ROOT/BankServer.kt",
            "src/main/resources/application.yaml",
            "$SOURCES_ROOT/Teller.kt",
        )
    }
}
