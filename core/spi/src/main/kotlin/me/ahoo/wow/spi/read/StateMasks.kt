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

package me.ahoo.wow.spi.read

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.WowSpi
import tools.jackson.databind.JsonNode

/**
 * The node's masks of its aggregates' states: each `@Sensitive` value of a state replaced by its mask, at every level,
 * as a read of the state returns it. A writer that stores a state where a reader takes it as a read, a cache or a sink,
 * masks it by these, so what it stores is what a read answers.
 *
 * The node's query side implements it (`me.ahoo.wow.query.mask.PayloadMasks`), with the Manifest's masks and the
 * application's custom mask strategies.
 */
@WowSpi
fun interface StateMasks {
    /**
     * The mask of a state of [type], a state's JSON as `StateEventRecord.state` carries it: it masks the tree it is
     * given in place where it can and returns the masked state, so a writer gives it a tree of its own. Null when
     * [type]'s state schema masks nothing, so such a state is written as it is.
     */
    fun maskOf(type: AggregateType): ((JsonNode) -> JsonNode)?
}
