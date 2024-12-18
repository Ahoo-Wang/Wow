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

package me.ahoo.wow.query.context

import me.ahoo.wow.api.modeling.NamedAggregate

const val QUERY_KEY = "__QUERY__"
const val RESULT_KEY = "__RESULT__"

@Suppress("UNCHECKED_CAST")
interface QueryContext<SOURCE : QueryContext<SOURCE, Q, R>, Q : Any, R : Any> {
    val attributes: MutableMap<String, Any>
    val namedAggregate: NamedAggregate

    fun setQuery(query: Q): SOURCE {
        return setAttribute(QUERY_KEY, query)
    }

    fun getQuery(): Q {
        return checkNotNull(getAttribute<Q>(QUERY_KEY))
    }

    fun rewriteQuery(rewrite: (Q) -> Q): SOURCE {
        return setQuery(rewrite(getQuery()))
    }

    fun setResult(result: R): SOURCE {
        return setAttribute(RESULT_KEY, result)
    }

    fun getRequiredResult(): R {
        return checkNotNull(getAttribute<R>(RESULT_KEY))
    }

    fun rewriteResult(rewrite: (R) -> R): SOURCE {
        return setResult(rewrite(getRequiredResult()))
    }

    fun setAttribute(key: String, value: Any): SOURCE {
        attributes[key] = value
        return this as SOURCE
    }

    fun <V> getAttribute(key: String): V? {
        return attributes[key] as V?
    }
}
