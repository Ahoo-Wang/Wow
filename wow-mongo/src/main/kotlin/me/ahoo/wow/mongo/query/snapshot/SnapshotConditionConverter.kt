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

package me.ahoo.wow.mongo.query.snapshot

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.mongo.query.AbstractMongoConditionConverter
import me.ahoo.wow.query.converter.FieldConverter
import org.bson.conversions.Bson

object SnapshotConditionConverter : AbstractMongoConditionConverter() {
    override val fieldConverter: FieldConverter = SnapshotFieldConverter
    override fun aggregateId(condition: Condition): Bson {
        return id(condition)
    }

    override fun aggregateIds(condition: Condition): Bson {
        return ids(condition)
    }
}
