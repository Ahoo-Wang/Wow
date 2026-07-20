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

import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import java.math.BigDecimal
import java.util.*

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
    var bigDecimal: BigDecimal = BigDecimal.ZERO
    var item: Item = Item(id = "", name = "")
    var duration: java.time.Duration = java.time.Duration.ofHours(1)
    var kotlinDuration: kotlin.time.Duration = kotlin.time.Duration.parse("PT1H")
    var date = java.util.Date()
    var sqlDate = java.sql.Date(System.currentTimeMillis())
    var uuid: UUID = UUID.randomUUID()
    var localDate: java.time.LocalDate = java.time.LocalDate.now()
    var localDateTime: java.time.LocalDateTime = java.time.LocalDateTime.now()
    var localTime: java.time.LocalTime = java.time.LocalTime.now()
    var instant: java.time.Instant = java.time.Instant.now()
    var zonedDateTime: java.time.ZonedDateTime = java.time.ZonedDateTime.now()
    var offsetDateTime: java.time.OffsetDateTime = java.time.OffsetDateTime.now()
    var offsetTime: java.time.OffsetTime = java.time.OffsetTime.now()
    var yearMonth: java.time.YearMonth = java.time.YearMonth.now()
    var monthDay: java.time.MonthDay = java.time.MonthDay.now()
    var period: java.time.Period = java.time.Period.ZERO
    var year: java.time.Year = java.time.Year.now()
    var defaultEnum = DefaultWireEnum.VALUE
    var numericEnum = NumericWireEnum.VALUE
    var month = java.time.Month.JANUARY
    var dayOfWeek = java.time.DayOfWeek.MONDAY
    var nested: Nested = Nested(id = "", name = "", child = NestedChild(id = "", name = ""))
    var stringList: List<String> = emptyList()

    @Suppress("ArrayPrimitive") // Boxed Array<Int> is the wire-shape fixture under test.
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

enum class DefaultWireEnum {
    VALUE,
}

enum class NumericWireEnum(private val value: Int) {
    VALUE(1),
    ;

    @JsonValue
    fun toJsonValue(): Int = value
}
