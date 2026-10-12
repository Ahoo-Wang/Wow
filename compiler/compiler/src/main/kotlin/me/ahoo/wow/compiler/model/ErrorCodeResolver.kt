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

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import java.io.File

/** A rejection code of the module (decision D121): `@ErrorCode` on a `const val`, and its problems. */
internal data class ErrorCodeBinding(
    /** The property's qualified name. */
    val property: String,
    val error: ErrorManifest?,
    val problems: List<String>,
)

/**
 * Reads `@ErrorCode` properties (decision D121). KSP has no API for a property's value, so the code is read from the
 * source: the initializer must be one string literal, without templates.
 */
internal object ErrorCodeResolver {
    val ERROR_CODE: String = ErrorCode::class.java.name

    private val STATUSES = 400..599

    @OptIn(KspExperimental::class)
    fun resolve(property: KSPropertyDeclaration, stringType: KSType): ErrorCodeBinding {
        val name = property.qualifiedName?.asString() ?: property.simpleName.asString()
        val annotation = property.getAnnotationsByType(ErrorCode::class).first()
        if (Modifier.CONST !in property.modifiers || property.type.resolve() != stringType) {
            return ErrorCodeBinding(name, null, listOf("@ErrorCode property $name must be a const val of type String"))
        }
        // A property of the module's sources has a file location.
        val location = property.location as FileLocation
        val code = literal(File(location.filePath).readText(), location.lineNumber, property.simpleName.asString())
            ?: return ErrorCodeBinding(
                name,
                null,
                listOf(
                    "@ErrorCode property $name must be initialized with a string literal without templates: the " +
                        "compiler reads the code from the source",
                ),
            )
        val problems = buildList {
            if (code.isBlank()) {
                add("@ErrorCode property $name holds a blank code")
            }
            if (code.startsWith(FrameworkErrors.PREFIX)) {
                add("error code $code of $name takes the framework's prefix ${FrameworkErrors.PREFIX}")
            }
            if (annotation.status !in STATUSES) {
                add("error code $code of $name has status ${annotation.status}: a status is 400 to 599")
            }
        }
        return ErrorCodeBinding(
            name,
            ErrorManifest(code, annotation.status, annotation.description.ifBlank { null }),
            problems,
        )
    }

    /**
     * The string literal that initializes the property [name] declared at or after line [line] (1-based) of [source];
     * null when the initializer is not one string literal without templates.
     */
    fun literal(source: String, line: Int, name: String): String? {
        val start = source.lineSequence().take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 }
        val declaration = Regex("""\bval\s+`?${Regex.escape(name)}`?\s*(:\s*[\w.]+\s*)?=\s*""")
            .find(source, start.coerceAtMost(source.length)) ?: return null
        var index = declaration.range.last + 1
        if (source.getOrNull(index) != '"' || source.startsWith("\"\"\"", index)) {
            return null
        }
        index++
        val literal = StringBuilder()
        while (index < source.length) {
            when (val char = source[index]) {
                '"' -> return literal.toString().takeIf { endsDeclaration(source, index + 1) }
                '\\' -> {
                    val escaped = source.getOrNull(index + 1) ?: return null
                    if (escaped == 'u') {
                        val hex = source.substring(index + 2, (index + 6).coerceAtMost(source.length))
                        literal.append(hex.toIntOrNull(HEX)?.toChar() ?: return null)
                        index += UNICODE_ESCAPE_LENGTH
                    } else {
                        literal.append(ESCAPES[escaped] ?: return null)
                        index++
                    }
                }
                '$', '\n' -> return null
                else -> literal.append(char)
            }
            index++
        }
        return null
    }

    /** Whether nothing but a comment, a `;` or the end of the enclosing block follows the literal on its line. */
    private fun endsDeclaration(source: String, from: Int): Boolean {
        val rest = source.substring(from).substringBefore('\n').trim()
        return rest.isEmpty() || listOf("//", "/*", ";", "}").any { rest.startsWith(it) }
    }

    private const val HEX = 16

    /** `\uXXXX` is six characters; the loop moves past the last. */
    private const val UNICODE_ESCAPE_LENGTH = 5
    private val ESCAPES = mapOf(
        't' to '\t',
        'b' to '\b',
        'n' to '\n',
        'r' to '\r',
        '\'' to '\'',
        '"' to '"',
        '\\' to '\\',
        '$' to '$',
    )
}
