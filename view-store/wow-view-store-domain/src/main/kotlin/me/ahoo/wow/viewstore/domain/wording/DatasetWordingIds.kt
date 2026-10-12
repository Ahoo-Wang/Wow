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

package me.ahoo.wow.viewstore.domain.wording

import java.security.MessageDigest

/**
 * The id of one tenant's wording over one dataset in one application: the same three always give the same id, so the
 * wording route finds it without a lookup.
 */
object DatasetWordingIds {
    private const val SEPARATOR = '\u0000'
    private const val ID_LENGTH = 32

    fun of(tenantId: String, appId: String, dataset: String): String {
        val source = listOf(tenantId, appId, dataset).joinToString(SEPARATOR.toString())
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
    }
}
