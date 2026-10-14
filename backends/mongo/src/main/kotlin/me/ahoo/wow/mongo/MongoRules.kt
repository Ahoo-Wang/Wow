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

package me.ahoo.wow.mongo

import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.json.WowJson
import org.bson.Document

/**
 * A schedule's rule (decision D198) as the MongoDB stores keep it: the sub-document Wow's JSON writes it as, `{type,
 * ...}` (`{type: "after", millis: 600000}`). The timers and the snapshots write it alike.
 */
internal object MongoRules {
    /** [rule] as its sub-document. */
    fun document(rule: ScheduleRule): Document = Document(WowJson.mapper.convertValue(rule, MAP))

    /** The rule [document] holds. */
    fun of(document: Document): ScheduleRule = WowJson.mapper.convertValue(document, ScheduleRule::class.java)

    @Suppress("UNCHECKED_CAST")
    private val MAP: Class<Map<String, Any?>> = Map::class.java as Class<Map<String, Any?>>
}
