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

package me.ahoo.wow.compiler.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.util.spi.ToolProvider

/** A compiled class: its binary [name] and the [sourceFile] it was compiled from (its `SourceFile` attribute). */
data class CompiledClass(val name: String, val sourceFile: String)

/**
 * Which classes of a directory carry `@WowGenerated`, read with the JDK's `javap`: an annotation of class retention is
 * invisible to reflection. `javap -v` prints a class's own attributes after its body, so the mark is looked for there,
 * never in a method's attributes or the constant pool.
 */
object ClassMarks {
    private const val MARK = "me.ahoo.wow.api.annotation.WowGenerated"
    private val javap = ToolProvider.findFirst("javap").orElseThrow()

    /** Each class under [directory], with whether it carries the mark. */
    fun of(directory: File): Map<CompiledClass, Boolean> =
        directory.walk().filter { it.isFile && it.extension == "class" }.associate { file ->
            val text = disassembled(file)
            val name = file.relativeTo(directory).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val source = SOURCE_FILE.find(text)?.groupValues?.get(1).orEmpty()
            CompiledClass(name, source) to text.substringAfterLast("\n}").contains(MARK)
        }

    private fun disassembled(file: File): String {
        val out = ByteArrayOutputStream()
        val code = PrintWriter(out, true).use { javap.run(it, it, "-v", file.path) }
        check(code == 0) { "javap failed on $file: $out" }
        return out.toString()
    }

    private val SOURCE_FILE = Regex("""SourceFile: "([^"]+)"""")
}
