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

package me.ahoo.wow.query.index

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest

/**
 * How a snapshot query backend manages the indexes the Manifest declares (`x-wow-index`, decision D151), the setting
 * `wow.query.index.ddl`. Every backend reads the same setting; what makes an index broken is the backend's to say.
 */
enum class QueryIndexDdl(val value: String) {
    /** Creates the missing indexes, and rebuilds one the backend finds broken (left invalid, or defined otherwise). */
    AUTO("auto"),

    /** Reports the missing and broken indexes, and changes nothing. */
    VALIDATE("validate"),

    /** Does nothing. */
    NONE("none"),
    ;

    companion object {
        /** The setting's name. */
        const val PROPERTY: String = "wow.query.index.ddl"

        /** The mode named [value] (`auto`, `validate` or `none`, in any case). */
        fun of(value: String): QueryIndexDdl = entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("$PROPERTY must be one of auto, validate or none, not [$value].")
    }
}

/**
 * One `x-wow-index` entry of an aggregate's state schema (decision D151): its name and its keys, logical paths. Each
 * snapshot query backend plans its own index from it.
 */
class QueryIndexDeclaration @InternalWowApi constructor(val name: String, val keys: List<String>) {
    init {
        require(name.isNotEmpty()) { "An index needs a name." }
        require(keys.isNotEmpty()) { "Index [$name] needs at least one key." }
    }

    companion object {
        /** The keyword `manifest-tools` writes the indexes under, at the root of the state schema. */
        const val KEYWORD: String = "x-wow-index"

        /** The indexes the state of [aggregate] in [context] declares in [manifest]; none when it declares none. */
        fun of(manifest: Manifest, context: String, aggregate: String): List<QueryIndexDeclaration> {
            val state = manifest.contexts.firstOrNull { it.name == context }
                ?.aggregates?.firstOrNull { it.name == aggregate }?.state ?: return emptyList()
            val schema = manifest.defs[Manifest.schemaKey(state.schema)] as? Map<*, *> ?: return emptyList()
            val entries = schema[KEYWORD] ?: return emptyList()
            require(entries is List<*>) { "$KEYWORD of $context.$aggregate must be an array." }
            return entries.map { entry ->
                require(entry is Map<*, *>) { "An entry of $KEYWORD of $context.$aggregate must be an object." }
                val name = entry["name"]
                val keys = entry["keys"]
                require(name is String && keys is List<*> && keys.all { it is String }) {
                    "An entry of $KEYWORD of $context.$aggregate needs a name and string keys."
                }
                QueryIndexDeclaration(name, keys.map { it as String })
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryIndexDeclaration && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "QueryIndexDeclaration(name=$name, keys=$keys)"

    private fun valueFields(): List<Any?> = listOf(name, keys)
}
