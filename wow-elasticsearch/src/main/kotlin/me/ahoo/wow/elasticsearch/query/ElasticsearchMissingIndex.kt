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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.ElasticsearchException

private const val INDEX_NOT_FOUND = "index_not_found_exception"

/**
 * Whether Elasticsearch answered that the index does not exist. Wow creates an aggregate's index on its first write
 * (from the templates, or from its index definition at startup), so before that write the index is missing: a read
 * of it holds no record, as the snapshot store's own load reads it.
 */
internal fun Throwable.isIndexNotFound(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is ElasticsearchException && it.error().type() == INDEX_NOT_FOUND }

private const val MAX_CAUSE_DEPTH = 8
