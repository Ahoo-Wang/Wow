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

package me.ahoo.wow.bi.expansion

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.expansion.StateExpansionScriptGenerator.Companion.toScriptGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import java.util.*

class StateExpansionMetadataVisitorTest {
    private val expectedBiAggregateScript =
        this.javaClass.classLoader.getResource("expected_bi_aggregate_script.sql")?.readText()

    @Test
    fun build() {
        val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()
        val scriptGenerator = biAggregateMetadata.toScriptGenerator()
        val sql = scriptGenerator.toString().trim()
        sql.assert().isEqualTo(expectedBiAggregateScript)
        scriptGenerator.targetTables.assert().containsExactly(
            "bi_aggregate_state_last_root",
            "bi_aggregate_state_last_root_items",
            "bi_aggregate_state_last_root_like_list_item",
            "bi_aggregate_state_last_root_nested_list",
            "bi_aggregate_state_last_root_nested_list_list",
            "bi_aggregate_state_last_root_set"
        )
    }

    @Test
    fun toJson() {
        val state = BIAggregateState(UUID.randomUUID().toString())
        val json = state.toJsonString()
        val stateObj = json.toObject<BIAggregateState>()
        stateObj.id.assert().isEqualTo(state.id)
        stateObj.string.assert().isEqualTo(state.string)
        stateObj.int.assert().isEqualTo(state.int)
        stateObj.long.assert().isEqualTo(state.long)
        stateObj.double.assert().isEqualTo(state.double)
        stateObj.float.assert().isEqualTo(state.float)
        stateObj.boolean.assert().isEqualTo(state.boolean)
        stateObj.byte.assert().isEqualTo(state.byte)
        stateObj.short.assert().isEqualTo(state.short)
        stateObj.char.assert().isEqualTo(state.char)
        stateObj.item.assert().isEqualTo(state.item)
        stateObj.uuid.assert().isEqualTo(state.uuid)
        stateObj.duration.assert().isEqualTo(state.duration)
        stateObj.kotlinDuration.assert().isEqualTo(state.kotlinDuration)
        stateObj.date.assert().isEqualTo(state.date)
        stateObj.sqlDate.assert().isEqualTo(state.sqlDate)
        stateObj.localDate.assert().isEqualTo(state.localDate)
        stateObj.localDateTime.assert().isEqualTo(state.localDateTime)
        stateObj.localTime.assert().isEqualTo(state.localTime)
        stateObj.instant.assert().isEqualTo(state.instant)
        stateObj.offsetTime.assert().isEqualTo(state.offsetTime)
        stateObj.yearMonth.assert().isEqualTo(state.yearMonth)
        stateObj.monthDay.assert().isEqualTo(state.monthDay)
        stateObj.period.assert().isEqualTo(state.period)
        stateObj.year.assert().isEqualTo(state.year)
        stateObj.month.assert().isEqualTo(state.month)
        stateObj.dayOfWeek.assert().isEqualTo(state.dayOfWeek)
        stateObj.nested.assert().isEqualTo(state.nested)
        stateObj.stringList.assert().isEqualTo(state.stringList)
        stateObj.intArray.assert().isEqualTo(state.intArray)
        stateObj.map.assert().isEqualTo(state.map)
        stateObj.items.assert().isEqualTo(state.items)
        stateObj.nestedList.assert().isEqualTo(state.nestedList)
    }
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
class BIAggregate(private val state: BIAggregateState)

class BIAggregateState(override val id: String) : Identifier {
    var string: String = ""
    var int: Int = 0
    var long: Long = 0
    var double: Double = 0.0
    var float: Float = 0.0f
    var boolean: Boolean = false
    var byte: Byte = 0
    var short: Short = 0
    var char: Char = ' '
    var item: Item = Item(id = "", name = "")
    var duration: java.time.Duration = java.time.Duration.ofHours(1)
    var kotlinDuration: kotlin.time.Duration = kotlin.time.Duration.parse("PT1H")
    var date = java.util.Date()
    var sqlDate = java.sql.Date(System.currentTimeMillis())
    var uuid = UUID.randomUUID()
    var localDate = java.time.LocalDate.now()
    var localDateTime = java.time.LocalDateTime.now()
    var localTime = java.time.LocalTime.now()
    var instant = java.time.Instant.now()
    var zonedDateTime = java.time.ZonedDateTime.now()
    var offsetDateTime = java.time.OffsetDateTime.now()
    var offsetTime = java.time.OffsetTime.now()
    var yearMonth = java.time.YearMonth.now()
    var monthDay = java.time.MonthDay.now()
    var period = java.time.Period.ZERO
    var year = java.time.Year.now()
    var month = java.time.Month.JANUARY
    var dayOfWeek = java.time.DayOfWeek.MONDAY
    var nested: Nested = Nested(id = "", name = "", child = NestedChild(id = "", name = ""))
    var stringList: List<String> = emptyList()
    var intArray: Array<Int> = kotlin.emptyArray()
    var map: Map<String, String> = emptyMap()
    var mapItem: Map<String, Item> = emptyMap()
    var items: List<Item> = emptyList()
    var set: Set<Item> = emptySet()
    var likeLinkString = LikeLinkString()
    var likeListItem = LikeListItem()
    var likeMapString = LikeMapString()
    var likeMapItem = LikeMapItem()
    var nestedList: List<NestedList> = emptyList()
}

data class Item(val id: String, val name: String)
data class NestedList(val id: String, val name: String, val list: List<Nested>)
data class Nested(val id: String, val name: String, val child: NestedChild)
data class NestedChild(val id: String, val name: String)

class LikeLinkString : LinkedList<String>()
class LikeListItem : LinkedList<Item>()
class LikeMapString : HashMap<String, String>()
class LikeMapItem : HashMap<String, Item>()
