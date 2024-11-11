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

package me.ahoo.wow.elasticsearch

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.toStringWithAlias

object IndexNameConverter {
    const val SNAPSHOT_SUFFIX = ".snapshot"
    const val EVENT_STREAM_SUFFIX = ".es"
    const val AGGREGATE_ID_SUFFIX = ".id"

    fun NamedAggregate.toSnapshotIndexName(): String {
        return "${Wow.WOW_PREFIX}${this.toStringWithAlias()}$SNAPSHOT_SUFFIX"
    }

    fun NamedAggregate.toEventStreamIndexName(): String {
        return "${Wow.WOW_PREFIX}${this.toStringWithAlias()}$EVENT_STREAM_SUFFIX"
    }

    fun NamedAggregate.toAggregateIdIndexName(): String {
        return "${Wow.WOW_PREFIX}${this.toStringWithAlias()}$AGGREGATE_ID_SUFFIX"
    }
}
