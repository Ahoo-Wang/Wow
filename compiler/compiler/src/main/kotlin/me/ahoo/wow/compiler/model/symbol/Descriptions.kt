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

package me.ahoo.wow.compiler.model.symbol

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.KSDeclaration
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary

/**
 * What a type of the model says it is, for the Manifest (.1c): its `@Description`, else the summary of its
 * KDoc, else its `@Summary`; null when it says nothing or only blanks.
 *
 * KDoc is source text: the compiler reads it only for a type compiled in the module it processes. A type it reads
 * from a dependency (a command of an `-api` module) is described by its annotations alone.
 */
internal object Descriptions {
    private val BLANK_LINE = Regex("\\n\\s*\\n")
    private val WHITESPACE = Regex("\\s+")

    /** `[label][Target]`, a KDoc link with its own text. */
    private val LABELLED_LINK = Regex("\\[([^\\[\\]]+)]\\[[^\\[\\]]+]")

    /** `[Target]`, a KDoc link written as its target. */
    private val LINK = Regex("\\[([^\\[\\]]+)]")

    @OptIn(KspExperimental::class)
    fun of(declaration: KSDeclaration): String? =
        declaration.getAnnotationsByType(Description::class).firstOrNull()?.value?.trim()?.ifEmpty { null }
            ?: summary(declaration.docString)
            ?: declaration.getAnnotationsByType(Summary::class).firstOrNull()?.value?.trim()?.ifEmpty { null }

    /**
     * The first paragraph of the KDoc [docString], up to a blank line or its first block tag (`@param`), on one line,
     * its links written as their text; null when it has none.
     */
    fun summary(docString: String?): String? {
        if (docString == null) {
            return null
        }
        val lines = docString.removePrefix("/**").removeSuffix("*/").lines()
            .map { it.trim().removePrefix("*").trim() }
            .takeWhile { !it.startsWith("@") }
        val paragraph = lines.joinToString("\n").trim().split(BLANK_LINE).first()
        return paragraph.replace(LABELLED_LINK, "$1").replace(LINK, "$1").replace(WHITESPACE, " ").trim()
            .ifEmpty { null }
    }
}
