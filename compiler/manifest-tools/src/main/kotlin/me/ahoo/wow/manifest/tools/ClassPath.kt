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

import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The classes of a class path's [entries], class directories and jars, by binary name, without loading one: what the
 * code generated for an api module compiles against. A JDK class is always there.
 */
internal class ClassPath(private val entries: List<File>) {
    private val jars: List<Set<String>> by lazy {
        entries.filter { it.isFile }.map { jar ->
            try {
                ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
            } catch (_: ZipException) {
                emptySet()
            }
        }
    }

    /** Whether the class [binary] (`a.b.Outer$Inner`) is on the class path. */
    operator fun contains(binary: String): Boolean {
        if (JDK_PACKAGES.any { binary.startsWith(it) }) return true
        val path = binary.replace('.', '/') + ".class"
        return entries.any { it.isDirectory && File(it, path).isFile } || jars.any { path in it }
    }

    private companion object {
        val JDK_PACKAGES = listOf("java.", "javax.")
    }
}
