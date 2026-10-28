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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClassPathTest {
    @TempDir
    lateinit var directory: File

    private fun jar(name: String, vararg entries: String): File = File(directory, name).also { jar ->
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `a class should be found in a class directory or a jar, a nested one by its binary name`() {
        val classes = File(directory, "classes").apply { mkdirs() }
        val notAJar = File(directory, "notes.txt").apply { writeText("not a jar") }
        val missing = File(directory, "missing.jar")
        val other = jar("other.jar", "sample/Other.class", "sample/Outer\$Inner.class")
        val classPath = ClassPath(listOf(classes, notAJar, missing, other))
        ("sample.Other" in classPath).assert().isTrue()
        ("sample.Outer\$Inner" in classPath).assert().isTrue()
        ("sample.Absent" in classPath).assert().isFalse()
        File(classes, "sample/Mine.class").apply { parentFile.mkdirs() }.writeText("")
        ("sample.Mine" in classPath).assert().isTrue()
    }

    @Test
    fun `a JDK class should always be on the class path`() {
        ("java.time.Instant" in ClassPath(emptyList())).assert().isTrue()
    }
}
