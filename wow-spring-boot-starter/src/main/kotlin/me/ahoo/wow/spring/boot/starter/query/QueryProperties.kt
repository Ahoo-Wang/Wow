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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = QueryProperties.PREFIX)
class QueryProperties @ConstructorBinding constructor(
    @DefaultValue
    var schema: Schema,
    @DefaultValue
    var cursor: Cursor = Cursor(null),
) {
    data class Schema @ConstructorBinding constructor(
        @DefaultValue("COMPATIBLE")
        var validationMode: QuerySchemaValidationMode,
    )

    data class Cursor @ConstructorBinding constructor(
        var encryptionKey: String?,
    ) {
        init {
            tokenCodec()
        }

        fun tokenCodec(): CursorTokenCodec? = encryptionKey
            ?.takeIf(String::isNotBlank)
            ?.let(CursorTokenCodec::fromBase64Url)
    }

    fun cursorTokenCodec(): CursorTokenCodec? = cursor.tokenCodec()

    companion object {
        const val PREFIX = "wow.query"
        const val CURSOR_ENCRYPTION_KEY = "$PREFIX.cursor.encryption-key"
    }
}
