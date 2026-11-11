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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.clickhouse.sink.ClickHouseSql.identifier
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.spi.access.AccessValues

/**
 * A column of `_state` that ClickHouse computes from its stored columns when a row is inserted (`MATERIALIZED`): its
 * [name], its [type] as `system.columns` reads it, and the [expression] it is. Its comment keeps its [fingerprint]
 * (`wow:<fingerprint>`), the first 16 hex digits of the SHA-256 of its type and expression, by which a writer tells a
 * column it expects from one of another definition: `system.columns` reads the expression back reformatted.
 */
@InternalWowApi
data class ComputedColumn(val name: String, val type: String, val expression: String) {
    val fingerprint: String by lazy { ClickHouseCatalog.sha256("$type\n$expression").take(FINGERPRINT_LENGTH) }

    /** What the column's comment says. */
    val comment: String get() = PREFIX + fingerprint

    /** The column's definition in a `CREATE TABLE` or an `ADD COLUMN`. */
    val definition: String
        get() = "${identifier(name)} $type MATERIALIZED $expression COMMENT ${ClickHouseSql.literal(comment)}"

    @InternalWowApi
    companion object {
        /** What a computed column's comment starts with. */
        const val PREFIX: String = "wow:"
        private const val FINGERPRINT_LENGTH = 16

        /** The computed column a flat column of the state is on `_state`. */
        fun of(column: FlatColumn): ComputedColumn = ComputedColumn(column.name, column.type, column.expression)
    }
}

/**
 * The access columns of `_state`: one per access attribute, `access_<name>`, read from the stored `access` (the
 * attributes as JSON text) when a row is inserted: the built-in `owner` and `space` first, strings every aggregate has
 * ([BUILT_IN]), then each the Manifest declares. A declared attribute's name is never a built-in one's
 * ([me.ahoo.wow.manifest.AccessAttributeName]), so no two columns share a name.
 *
 * | Declared | Column type |
 * | --- | --- |
 * | `STRING`, `INTEGER`, `BOOLEAN` | `Nullable(String)`, `Nullable(Int64)`, `Nullable(Bool)` |
 * | the same, multiple | `Array(String)`, `Array(Int64)`, `Array(Bool)` |
 *
 * A row without the attribute (`access` empty, or no such key) has `NULL`, or an empty array.
 */
internal object AccessColumns {
    const val PREFIX: String = "access_"

    /** The built-in attributes, each a single string. */
    val BUILT_IN: List<AccessAttributeManifest> = listOf(AccessValues.OWNER, AccessValues.SPACE).map {
        AccessAttributeManifest(it, AccessAttributeManifest.Type.STRING)
    }

    fun of(attributes: List<AccessAttributeManifest>): List<ComputedColumn> = (BUILT_IN + attributes).map { attribute ->
        val element = when (attribute.type) {
            AccessAttributeManifest.Type.STRING -> "String"
            AccessAttributeManifest.Type.INTEGER -> "Int64"
            AccessAttributeManifest.Type.BOOLEAN -> "Bool"
        }
        val type = if (attribute.multiple) "Array($element)" else "Nullable($element)"
        ComputedColumn(
            PREFIX + attribute.name,
            type,
            "JSONExtract(access, ${ClickHouseSql.literal(attribute.name)}, ${ClickHouseSql.literal(type)})",
        )
    }
}
