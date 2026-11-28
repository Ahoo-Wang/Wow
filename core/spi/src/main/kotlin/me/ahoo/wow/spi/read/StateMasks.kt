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
 * The node's masks of what its aggregates commit, a state and an event's body: each `@Sensitive` value replaced by its
 * mask, at every level, as a read of them returns it. A writer that stores or publishes them where a reader takes them
 * as a read masks them by these: the state view cache's sink, as a read of the owner returns the state, and the
 * transport sink, which publishes them to other contexts.
 *
 * A mask is asked for as a read that reveals `DISPLAY` values or as one that does not ([revealsDisplay]): one that
 * reveals them masks the `CONFIDENTIAL` values alone.
 *
 * The node's query side implements it (`me.ahoo.wow.query.mask.PayloadMasks`), with the Manifest's masks and the
 * application's custom mask strategies.
 */
@WowSpi
interface StateMasks {
    /**
     * The mask of a state of [type], a state's JSON as `StateEventRecord.state` carries it: it masks the tree it is
     * given in place where it can and returns the masked state, so a writer gives it a tree of its own. Null when
     * [type]'s state schema masks nothing, so such a state is written as it is.
     */
    fun stateMaskOf(type: AggregateType, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)?

    /**
     * The mask of the body of [type]'s event named [event], its name in the log (`<context>.<aggregate>.<Name>`) or
     * the Manifest's, or one of its aliases: as [stateMaskOf]'s, in place where it can. Null when the event's schema
     * masks nothing, so such a body is written as it is. An event the Manifest has no schema for is masked whole: the
     * node cannot tell what in it is sensitive.
     */
    fun eventMaskOf(type: AggregateType, event: String, revealsDisplay: Boolean): ((JsonNode) -> JsonNode)?
}
