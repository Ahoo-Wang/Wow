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

package me.ahoo.wow.query.schema

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * compat(wow<9.2): what a 9.1 declaration file at `wow-query-schema/{context}/{aggregate}/{model}.json` means when no
 * 9.2 declaration of that model exists in any source (classpath, working directory or registration bean). 9.2 does
 * not read the 9.1 format, so such a model is served from its inferred schema.
 *
 * While 9.1 and 9.2 nodes run side by side the file may still be needed by the 9.1 nodes, so the default only warns.
 * Once every node runs 9.2, [FAIL] makes a forgotten file fail that model's schema instead.
 */
enum class LegacyQuerySchemaDeclarationPolicy {
    /** Log a warning naming where the 9.2 declaration goes; the model uses its inferred schema. */
    WARN,

    /** Fail the model's schema with [QuerySchemaUnavailableException]. */
    FAIL,
}

/**
 * compat(wow<9.2): checks the 9.1 declaration files of one model ([legacy], as the sources listed them) against
 * whether any source supplied a 9.2 declaration ([declared]). Each distinct finding is logged once per model.
 */
internal class LegacyQuerySchemaDeclarations(
    private val context: QuerySchemaContext,
    private val policy: LegacyQuerySchemaDeclarationPolicy,
) {
    @Volatile
    private var reported: Pair<List<String>, Boolean>? = null

    fun check(legacy: List<String>, declared: Boolean) {
        if (legacy.isEmpty()) {
            reported = null
            return
        }
        val target = "$QUERY_SCHEMA_FEATURE/${context.resourceKey()}.json"
        if (!declared && policy == LegacyQuerySchemaDeclarationPolicy.FAIL) {
            throw QuerySchemaUnavailableException(
                "Query schema declaration $legacy is at the 9.1 location, which 9.2 does not read. " +
                    "Move it to [config/wow/$target] or the classpath [META-INF/wow/$target] in the 9.2 format.",
            )
        }
        val finding = legacy to declared
        if (reported == finding) return
        reported = finding
        if (declared) {
            log.info { "Ignoring the 9.1 query schema declaration $legacy of [$context]: a 9.2 declaration exists." }
        } else {
            log.warn {
                "Query schema declaration $legacy is at the 9.1 location, which 9.2 does not read; [$context] uses " +
                    "its inferred schema. Once no 9.1 node reads it, move it to [config/wow/$target] or the " +
                    "classpath [META-INF/wow/$target] in the 9.2 format (wow.query.schema.legacy-declarations=fail " +
                    "fails the schema instead)."
            }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger { }
    }
}

/** The 9.1 declaration files [source] lists for [context]; only the built-in file sources have any. */
internal fun QuerySchemaSource.listLegacyDeclarations(context: QuerySchemaContext): List<String> = when (this) {
    is ClasspathQuerySchemaSource -> legacyDeclarations(context)
    is WorkingDirectoryQuerySchemaSource -> legacyDeclarations(context)
    else -> emptyList()
}
