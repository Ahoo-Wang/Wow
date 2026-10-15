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

package me.ahoo.wow.viewstore.domain

import java.security.MessageDigest

/**
 * The ids the view store derives from what an aggregate is for, so a route finds the aggregate without a lookup:
 * the same parts always give the same id. Ids are unique per tenant in V10 (axiom A6), so the tenant is not one of
 * the parts, as it was in 9.x.
 */
object ScopedIds {
    private const val SEPARATOR = '\u0000'
    private const val ID_LENGTH = 32

    /** The id of [ownerId]'s preferences for [definitionId] in [appId]; an owner-less caller's are its tenant's. */
    fun preferences(ownerId: String?, appId: String, definitionId: String): String =
        of("preferences", ownerId.orEmpty(), appId, definitionId)

    /** The id of the wording over [dataset] in [appId]. */
    fun wording(appId: String, dataset: String): String = of("wording", appId, dataset)

    private fun of(vararg parts: String): String {
        val source = parts.joinToString(SEPARATOR.toString())
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
    }
}
