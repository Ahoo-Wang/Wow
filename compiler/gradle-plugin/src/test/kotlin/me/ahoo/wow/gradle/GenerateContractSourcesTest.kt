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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenerateContractSourcesTest {
    @TempDir
    lateinit var directory: File

    private val queryField = "me/ahoo/wow/api/query/QueryField.class"

    private fun jar(name: String, vararg entries: String): File = File(directory, name).also { jar ->
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `query fields should need wow-query-api on the class path`() {
        val classes = File(directory, "classes").apply { mkdirs() }
        val notAJar = File(directory, "notes.txt").apply { writeText("not a jar") }
        val missing = File(directory, "missing.jar")
        val other = jar("other.jar", "sample/Other.class")
        GenerateContractSources.hasQueryApi(listOf(classes, notAJar, missing, other)).assert().isFalse()
        GenerateContractSources.hasQueryApi(listOf(other, jar("query-api.jar", queryField))).assert().isTrue()
        File(classes, queryField).apply { parentFile.mkdirs() }.writeText("")
        GenerateContractSources.hasQueryApi(listOf(classes)).assert().isTrue()
    }
}
