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

package me.ahoo.wow.spi.id

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.UUID

/**
 * The one encoder of every id Wow derives from what a request asks: a saga's derived commands, its
 * compensations, and the commands a decide function sends. Each field is written as its length, `:`, and itself, so
 * two different lists of fields never give one text, whatever characters the fields hold (a `/` or `#` in a tenant,
 * an id or a business key), and two different requests never share an id.
 */
@InternalWowApi
object DerivedIds {
    /** The one text of [fields], in order: each as its length, `:`, and itself, so they join one way only. */
    fun encoded(vararg fields: String): String = buildString {
        fields.forEach { append(it.length).append(':').append(it) }
    }

    /** A name-based UUID of [name]. */
    fun idOf(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
}
