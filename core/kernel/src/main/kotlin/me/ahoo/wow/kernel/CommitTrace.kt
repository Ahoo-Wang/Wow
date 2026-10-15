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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.log.EventStreamRecord
import tools.jackson.databind.JsonNode

/**
 * One commit of an aggregate's history ([Kernel.trace], decision D40): its [stream], the stream's [events] (but the
 * retired ones, decision D219) read as the
 * model's current schema (upgraded, D82), and the aggregate after it: its [state] as the event codec writes it (a
 * tree, so a mutable state evolving further does not change it), its [deleted] mark and its [tags].
 */
@InternalWowApi
class CommitTrace(
    val stream: EventStreamRecord,
    val events: List<Any>,
    val state: JsonNode,
    val deleted: Boolean,
    val tags: Map<String, List<String>>,
) {
    /** The aggregate's version after the commit, the stream's. */
    val version: Int get() = stream.version

    /** The aggregate's scope after the commit, the one the stream records (D56). */
    val scope: Scope get() = stream.scope
}
