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

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorCause
import org.springframework.data.elasticsearch.RestStatusException
import java.util.Collections
import java.util.IdentityHashMap

internal fun Throwable.isMissingElasticsearchSearchContext(): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        when (current) {
            is ElasticsearchException -> {
                if (current.status() == NOT_FOUND || current.error().containsMissingSearchContext()) return true
            }

            is RestStatusException -> if (current.status == NOT_FOUND) return true
        }
        current = current.cause
    }
    return false
}

private fun ErrorCause.containsMissingSearchContext(): Boolean {
    val pending = ArrayDeque<ErrorCause>()
    val visited = Collections.newSetFromMap(IdentityHashMap<ErrorCause, Boolean>())
    pending.add(this)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current)) continue
        if (current.type() == SEARCH_CONTEXT_MISSING_TYPE) return true
        current.causedBy()?.let(pending::addLast)
        pending.addAll(current.rootCause())
        pending.addAll(current.suppressed())
    }
    return false
}

private const val NOT_FOUND = 404
private const val SEARCH_CONTEXT_MISSING_TYPE = "search_context_missing_exception"
