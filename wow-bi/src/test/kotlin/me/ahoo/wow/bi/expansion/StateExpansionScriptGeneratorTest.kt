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
import java.time.Duration
import java.util.*

class StateExpansionMetadataVisitorTest {

    @Test
    fun build() {
        val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()
        val scriptGenerator = biAggregateMetadata.toScriptGenerator()
        val sql = scriptGenerator.toString()
        assertThat(sql, equalTo(EXPECTED_SCRIPT))
        assertThat(
            scriptGenerator.targetTables,
            containsInAnyOrder(
                "bi_aggregate_state_last_root",
                "bi_aggregate_state_last_root_items",
                "bi_aggregate_state_last_root_set",
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
        assertThat(stateObj.intList, equalTo(state.intList))
        assertThat(stateObj.map, equalTo(state.map))
        assertThat(stateObj.items, equalTo(state.items))
        assertThat(stateObj.nestedList, equalTo(state.nestedList))
    }
}

private const val EXPECTED_SCRIPT =
    """CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_items ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'items')) AS items
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(items,'id', 'String') AS items_id,
JSONExtract(items,'name', 'String') AS items_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_set ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'set')) AS set
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(set,'id', 'String') AS set_id,
JSONExtract(set,'name', 'String') AS set_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_nested_list ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'nestedList')) AS nested_list
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(nested_list,'id', 'String') AS nested_list_id,
JSONExtract(nested_list,'name', 'String') AS nested_list_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_nested_list_list ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'nestedList')) AS nested_list,
arrayJoin(JSONExtractArrayRaw(nested_list, 'list')) AS nested_list_list,
JSONExtractString(nested_list_list,'child') AS nested_list_list_child
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(nested_list,'id', 'String') AS nested_list_id,
JSONExtract(nested_list,'name', 'String') AS nested_list_name,
JSONExtract(nested_list_list,'id', 'String') AS nested_list_list_id,
JSONExtract(nested_list_list,'name', 'String') AS nested_list_list_name,
JSONExtract(nested_list_list_child,'id', 'String') AS nested_list_list_child_id,
JSONExtract(nested_list_list_child,'name', 'String') AS nested_list_list_child_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;
"""

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
    var duration: Duration = Duration.ofHours(1)
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
    var intList: List<Int> = emptyList()
    var map: Map<String, String> = emptyMap()
    var items: List<Item> = emptyList()
    var set: Set<Item> = emptySet()
    var nestedList: List<NestedList> = emptyList()
}

data class Item(val id: String, val name: String)
data class NestedList(val id: String, val name: String, val list: List<Nested>)
data class Nested(val id: String, val name: String, val child: NestedChild)
data class NestedChild(val id: String, val name: String)
