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

package me.ahoo.wow.bi.expansion.column

data class MetadataColumn(
    override val name: String,
    override val type: Class<*>,
    override val sqlType: String
) : Column {
    override val parent: Column?
        get() = null
    override val isSimple: Boolean
        get() = true
    override val targetName: String
        get() = "__${super.targetName}"

    companion object {
        val ID_COLUMN = MetadataColumn("id", String::class.java, "String")
        val AGGREGATE_ID_COLUMN = MetadataColumn("aggregate_id", String::class.java, "String")
        val TENANT_ID_COLUMN = MetadataColumn("tenant_id", String::class.java, "String")
        val COMMAND_ID_COLUMN = MetadataColumn("command_id", String::class.java, "String")
        val REQUEST_ID_COLUMN = MetadataColumn("request_id", String::class.java, "String")
        val VERSION_COLUMN = MetadataColumn("version", Int::class.java, "Int32")
        val STATE_COLUMN = MetadataColumn("state", String::class.java, "JSON")
        val FIRST_OPERATOR_COLUMN = MetadataColumn("first_operator", String::class.java, "String")
        val FIRST_EVENT_TIME_COLUMN = MetadataColumn("first_event_time", Long::class.java, "DateTime('Asia/Shanghai')")
        val CREATE_TIME_COLUMN = MetadataColumn("create_time", Long::class.java, "DateTime('Asia/Shanghai')")
        val DELETED_COLUMN = MetadataColumn("deleted", Boolean::class.java, "Bool")
    }
}
