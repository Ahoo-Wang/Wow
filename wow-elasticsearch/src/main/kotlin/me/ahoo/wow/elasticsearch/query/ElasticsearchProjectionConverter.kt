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

import co.elastic.clients.elasticsearch.core.search.SourceFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.query.converter.ProjectionConverter

object ElasticsearchProjectionConverter : ProjectionConverter<SourceFilter> {
    override fun convert(projection: Projection): SourceFilter {
        return SourceFilter.of {
            it.includes(projection.include)
            it.excludes(projection.exclude)
        }
    }

    fun Projection.toSourceFilter(): SourceFilter {
        return convert(this)
    }
}
