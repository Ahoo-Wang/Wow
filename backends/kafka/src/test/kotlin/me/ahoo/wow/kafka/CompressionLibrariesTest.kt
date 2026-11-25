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

package me.ahoo.wow.kafka

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * wow-kafka's runtime class path holds kafka-clients' compression codecs at their patched versions at least: snappy-java
 * 1.1.10.9 and lz4-java 1.11.4, which wow-kafka declares while kafka-clients 4.4.0 brings older ones (GHSA-gwq9-9ffj-mvrv
 * and the others its build script names). The test's class path is the runtime class path and the test's own.
 */
class CompressionLibrariesTest {
    private val jars: List<String> = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map { File(it).name }

    private fun versionOf(artifact: String): List<Int> {
        val jar = jars.single { Regex("${Regex.escape(artifact)}-[0-9][0-9.]*\\.jar").matches(it) }
        return jar.removePrefix("$artifact-").removeSuffix(".jar").split('.').map(String::toInt)
    }

    private fun List<Int>.atLeast(minimum: List<Int>): Boolean {
        val first = zip(minimum).firstOrNull { (have, want) -> have != want } ?: return size >= minimum.size
        return first.first > first.second
    }

    @Test
    fun `the runtime class path holds snappy-java 1_1_10_9 and lz4-java 1_11_4 at least`() {
        versionOf("snappy-java").atLeast(listOf(1, 1, 10, 9)).assert().isTrue()
        versionOf("lz4-java").atLeast(listOf(1, 11, 4)).assert().isTrue()
    }
}
