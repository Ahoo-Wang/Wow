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

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.expansion.StateExpansionScriptGenerator.Companion.toScriptGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.util.*

class StateExpansionMetadataVisitorTest {
    private val expectedBiAggregateScript =
        this.javaClass.classLoader.getResource("expected_bi_aggregate_script.sql")?.readText()

    @Test
    fun build() {
        val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()
        val scriptGenerator = biAggregateMetadata.toScriptGenerator()
        val sql = scriptGenerator.toString()
        assertThat(sql, equalTo(expectedBiAggregateScript))
        assertThat(
            scriptGenerator.targetTables,
            containsInAnyOrder(
                "bi_aggregate_state_last_root",
                "bi_aggregate_state_last_root_items",
                "bi_aggregate_state_last_root_set",
                "bi_aggregate_state_last_root_like_list_item",
                "bi_aggregate_state_last_root_nested_list",
                "bi_aggregate_state_last_root_nested_list_list"
            )
        )
    }

    @Test
    fun toJson() {
        val state = BIAggregateState(UUID.randomUUID().toString())
        val json = state.toJsonString()
        val stateObj = json.toObject<BIAggregateState>()
        assertThat(stateObj.id, equalTo(state.id))
        assertThat(stateObj.string, equalTo(state.string))
        assertThat(stateObj.int, equalTo(state.int))
        assertThat(stateObj.long, equalTo(state.long))
        assertThat(stateObj.double, equalTo(state.double))
        assertThat(stateObj.float, equalTo(state.float))
        assertThat(stateObj.boolean, equalTo(state.boolean))
        assertThat(stateObj.byte, equalTo(state.byte))
        assertThat(stateObj.short, equalTo(state.short))
        assertThat(stateObj.char, equalTo(state.char))
        assertThat(stateObj.item, equalTo(state.item))
        assertThat(stateObj.uuid, equalTo(state.uuid))
        assertThat(stateObj.duration, equalTo(state.duration))
        assertThat(stateObj.kotlinDuration, equalTo(state.kotlinDuration))
        assertThat(stateObj.date, equalTo(state.date))
        assertThat(stateObj.sqlDate, equalTo(state.sqlDate))
        assertThat(stateObj.localDate, equalTo(state.localDate))
        assertThat(stateObj.localDateTime, equalTo(state.localDateTime))
        assertThat(stateObj.localTime, equalTo(state.localTime))
        assertThat(stateObj.instant, equalTo(state.instant))
        // assertThat(stateObj.zonedDateTime, equalTo(state.zonedDateTime))
        // assertThat(stateObj.offsetDateTime, equalTo(state.offsetDateTime))
        assertThat(stateObj.offsetTime, equalTo(state.offsetTime))
        assertThat(stateObj.yearMonth, equalTo(state.yearMonth))
        assertThat(stateObj.monthDay, equalTo(state.monthDay))
        assertThat(stateObj.period, equalTo(state.period))
        assertThat(stateObj.year, equalTo(state.year))
        assertThat(stateObj.month, equalTo(state.month))
        assertThat(stateObj.dayOfWeek, equalTo(state.dayOfWeek))
        assertThat(stateObj.nested, equalTo(state.nested))
        assertThat(stateObj.stringList, equalTo(state.stringList))
        assertThat(stateObj.intArray, equalTo(state.intArray))
        assertThat(stateObj.map, equalTo(state.map))
        assertThat(stateObj.items, equalTo(state.items))
        assertThat(stateObj.nestedList, equalTo(state.nestedList))
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
