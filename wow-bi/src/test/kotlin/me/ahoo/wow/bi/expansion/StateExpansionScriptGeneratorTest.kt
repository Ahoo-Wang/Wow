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
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class StateExpansionMetadataVisitorTest {

    @Test
    fun build() {
        val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()
        biAggregateMetadata.toScriptGenerator().toString()
        val sql = biAggregateMetadata.toScriptGenerator().toString()
        assertThat(sql, equalTo(EXPECTED_SCRIPT))
    }
}

private const val EXPECTED_SCRIPT = """CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root AS
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

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_items AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractString(state,'items')) AS items
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

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_nested_list AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractString(state,'nestedList')) AS nested_list
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

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_nested_list_list AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractString(state,'nestedList')) AS nested_list,
arrayJoin(JSONExtractString(nested_list,'list')) AS nested_list_list,
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
    var nested: Nested = Nested(id = "", name = "", child = NestedChild(id = "", name = ""))
    var stringList: List<String> = emptyList()
    var intList: List<Int> = emptyList()
    var map: Map<String, String> = emptyMap()
    var items: List<Item> = emptyList()
    var nestedList: List<NestedList> = emptyList()
}

data class Item(val id: String, val name: String)
data class NestedList(val id: String, val name: String, val list: List<Nested>)
data class Nested(val id: String, val name: String, val child: NestedChild)
data class NestedChild(val id: String, val name: String)
