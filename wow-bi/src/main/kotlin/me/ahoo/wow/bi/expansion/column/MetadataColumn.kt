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

import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JavaType

data class MetadataColumn(
    override val name: String,
    override val type: JavaType,
    override val sqlType: String
) : Column {
    override val parent: Column?
        get() = null
    override val isSimple: Boolean
        get() = true
    override val targetName: String
        get() = "__${super.targetName}"

    companion object {
        private val STRING_JAVA_TYPE = JsonSerializer.constructType(String::class.java)
        private val INT_JAVA_TYPE = JsonSerializer.constructType(Int::class.java)
        private val LONG_JAVA_TYPE = JsonSerializer.constructType(Long::class.java)
        private val BOOLEAN_JAVA_TYPE = JsonSerializer.constructType(Boolean::class.java)
        val ID_COLUMN = MetadataColumn("id", STRING_JAVA_TYPE, "String")
        val AGGREGATE_ID_COLUMN = MetadataColumn("aggregate_id", STRING_JAVA_TYPE, "String")
        val TENANT_ID_COLUMN = MetadataColumn("tenant_id", STRING_JAVA_TYPE, "String")
        val OWNER_ID_COLUMN = MetadataColumn("owner_id", STRING_JAVA_TYPE, "String")
        val COMMAND_ID_COLUMN = MetadataColumn("command_id", STRING_JAVA_TYPE, "String")
        val REQUEST_ID_COLUMN = MetadataColumn("request_id", STRING_JAVA_TYPE, "String")
        val VERSION_COLUMN = MetadataColumn("version", INT_JAVA_TYPE, "Int32")
        val FIRST_OPERATOR_COLUMN = MetadataColumn("first_operator", STRING_JAVA_TYPE, "String")
        val FIRST_EVENT_TIME_COLUMN = MetadataColumn("first_event_time", LONG_JAVA_TYPE, "DateTime('Asia/Shanghai')")
        val CREATE_TIME_COLUMN = MetadataColumn("create_time", LONG_JAVA_TYPE, "DateTime('Asia/Shanghai')")
        val DELETED_COLUMN = MetadataColumn("deleted", BOOLEAN_JAVA_TYPE, "Bool")
    }
}
