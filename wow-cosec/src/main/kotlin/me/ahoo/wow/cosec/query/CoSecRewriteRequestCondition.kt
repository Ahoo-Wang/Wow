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

package me.ahoo.wow.cosec.query

import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor.SPACE_ID_KEY
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.webflux.route.command.getSpaceId
import me.ahoo.wow.webflux.route.query.AbstractRewriteRequestCondition
import org.springframework.web.reactive.function.server.ServerRequest

object CoSecRewriteRequestCondition : AbstractRewriteRequestCondition() {

    override fun ServerRequest.resolveSpaceId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        getSpaceId().ifNotBlank {
            return it
        }
        return this.headers().firstHeader(SPACE_ID_KEY)
    }
}
