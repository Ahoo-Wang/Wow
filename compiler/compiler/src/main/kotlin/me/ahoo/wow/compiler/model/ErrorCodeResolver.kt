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
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import java.io.File

/** A rejection code of the module (decision D121): `@ErrorCode` on a `const val`, and its problems. */
internal data class ErrorCodeBinding(
    /** The property's qualified name. */
    val property: String,
    /** The code as written: a local name without a dot (decision D259), or a full code. */
    val error: ErrorManifest?,
    val problems: List<String>,
    /** The qualified name of the `@AggregateRoot` class the property is declared in, if any. */
    val aggregateRoot: String? = null,
) {
    /**
     * The code as the Manifest names it: a local name qualified with its aggregate's context and name
     * (`context.aggregate.code`, [aggregates] giving each `@AggregateRoot` class's name), a code with a dot as written;
     * null when it has problems or its aggregate is unknown.
     */
    fun qualified(context: String, aggregates: Map<String, String>): ErrorManifest? {
        val error = error ?: return null
        if (ErrorCodeResolver.isFull(error.code)) {
            return error
        }
        val aggregate = aggregates[aggregateRoot] ?: return null
        return ErrorManifest("$context.$aggregate.${error.code}", error.status, error.description)
    }
}

/**
 * Reads `@ErrorCode` properties (decision D121): a Kotlin `const val`, or a Java `static final String` field (decision
 * D229 G-8). KSP has no API for a property's value, so the code is read from the source: the initializer must be one
 * string literal, without templates.
 *
 * A code without a dot is a local name (decision D259): it is declared within its `@AggregateRoot` class (in a Kotlin
 * `object` nested in it, its companion included, or as a Java field of it or of a class nested in it), and the
 * Manifest names it `context.aggregate.code` ([ErrorCodeBinding.qualified]). A code with a dot is a full code, as
 * written: one the context shares (`context.code`), or one declared outside an aggregate.
 */
internal object ErrorCodeResolver {
    val ERROR_CODE: String = ErrorCode::class.java.name
    private val AGGREGATE_ROOT: String = AggregateRoot::class.java.name

    /** Whether [code] is a full code: it has a dot. Otherwise it is a local name within an aggregate. */
    fun isFull(code: String): Boolean = '.' in code

    private val STATUSES = 400..599

    @OptIn(KspExperimental::class)
    fun resolve(property: KSPropertyDeclaration, stringType: KSType): ErrorCodeBinding {
        val name = property.qualifiedName?.asString() ?: property.simpleName.asString()
        val annotation = property.getAnnotationsByType(ErrorCode::class).first()
        val java = property.origin == Origin.JAVA
        if (!isConstant(property, java) || property.type.resolve().makeNotNullable() != stringType) {
            val form = if (java) "a static final String field" else "a const val of type String"
            return ErrorCodeBinding(name, null, listOf("@ErrorCode property $name must be $form"))
        }
        // A property of the module's sources has a file location.
        val location = property.location as FileLocation
        val source = File(location.filePath).readText()
        val code = literal(source, location.lineNumber, property.simpleName.asString(), java)
            ?: return ErrorCodeBinding(
                name,
                null,
                listOf(
                    "@ErrorCode property $name must be initialized with a string literal without templates: the " +
                        "compiler reads the code from the source",
                ),
            )
        val aggregateRoot = aggregateRootOf(property)
        val problems = buildList {
            if (code.isBlank()) {
                add("@ErrorCode property $name holds a blank code")
            } else if (!isFull(code) && aggregateRoot == null) {
                add(
                    "error code $code of $name is a local name, but $name is not declared within an @AggregateRoot " +
                        "class: declare it in an object nested in its aggregate root, which names its context and " +
                        "aggregate, or write the full code (context.aggregate.code, or context.code for one the " +
                        "context shares)",
                )
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
            aggregateRoot,
        )
    }

    /** The qualified name of the nearest `@AggregateRoot` class [property] is declared in; null when none is. */
    private fun aggregateRootOf(property: KSPropertyDeclaration): String? =
        generateSequence<KSDeclaration>(property.parentDeclaration) { it.parentDeclaration }
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { declaration ->
                declaration.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == AGGREGATE_ROOT
                }
            }?.qualifiedName?.asString()

    /** A Kotlin `const val`, or a Java field that is `static final`. */
    private fun isConstant(property: KSPropertyDeclaration, java: Boolean): Boolean =
        if (java) {
            Modifier.JAVA_STATIC in property.modifiers && Modifier.FINAL in property.modifiers
        } else {
            Modifier.CONST in property.modifiers
        }

    /**
     * The string literal that initializes the property [name] declared at or after line [line] (1-based) of [source],
     * a Kotlin `val` or, when [java], a Java `String` field; null when the initializer is not one string literal
     * without templates. A Java literal has no templates: `$` is itself.
     */
    fun literal(source: String, line: Int, name: String, java: Boolean = false): String? {
        var index = initializer(source, line, name, java) ?: return null
        if (source.getOrNull(index) != '"' || source.startsWith("\"\"\"", index)) {
            return null
        }
        index++
        val literal = StringBuilder()
        while (index < source.length) {
            when (val char = source[index]) {
                '"' -> return literal.toString().takeIf { endsDeclaration(source, index + 1) }
                '\\' -> index += escape(source, index, literal) ?: return null
                '\n' -> return null
                '$' -> if (java) literal.append(char) else return null
                else -> literal.append(char)
            }
            index++
        }
        return null
    }

    /**
     * Appends the escape sequence at [index] of [source] (its backslash) to [literal]; how many characters past the
     * backslash it took, or null when it is not one.
     */
    private fun escape(source: String, index: Int, literal: StringBuilder): Int? {
        val escaped = source.getOrNull(index + 1) ?: return null
        return if (escaped == 'u') {
            val hex = source.substring(index + 2, (index + 6).coerceAtMost(source.length))
            literal.append(hex.toIntOrNull(HEX)?.toChar() ?: return null)
            UNICODE_ESCAPE_LENGTH
        } else {
            literal.append(ESCAPES[escaped] ?: return null)
            1
        }
    }

    /** Where the initializer of [name] declared at or after line [line] of [source] starts; null when not found. */
    private fun initializer(source: String, line: Int, name: String, java: Boolean): Int? {
        val start = source.lineSequence().take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 }
        val pattern = if (java) {
            """\bString\s+${Regex.escape(name)}\s*=\s*"""
        } else {
            """\bval\s+`?${Regex.escape(name)}`?\s*(:\s*[\w.]+\s*)?=\s*"""
        }
        return Regex(pattern).find(source, start.coerceAtMost(source.length))?.let { it.range.last + 1 }
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
