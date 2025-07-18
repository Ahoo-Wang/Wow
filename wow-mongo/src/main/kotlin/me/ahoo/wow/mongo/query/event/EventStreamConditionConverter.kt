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

package me.ahoo.wow.mongo.query.event

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.mongo.query.AbstractMongoConditionConverter
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson

object EventStreamConditionConverter : AbstractMongoConditionConverter() {
    override val fieldConverter: FieldConverter = EventStreamFieldConverter
    override fun convert(condition: Condition): Bson {
        val convertedCondition = convertCondition(condition)
        return internalConvert(convertedCondition)
    }

    override fun aggregateId(condition: Condition): Bson {
        return Filters.eq(MessageRecords.AGGREGATE_ID, condition.value)
    }

    override fun aggregateIds(condition: Condition): Bson {
        return Filters.`in`(MessageRecords.AGGREGATE_ID, condition.valueAs<Iterable<String>>())
    }
}
