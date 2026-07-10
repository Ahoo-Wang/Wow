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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.naming.getContextAlias

internal class BiTableNaming(private val options: BiScriptOptions = BiScriptOptions()) {
    fun toTopicName(namedAggregate: NamedAggregate, suffix: String): String {
        return "${options.topicPrefix}${namedAggregate.getContextAlias()}.${namedAggregate.aggregateName}.$suffix"
    }

    fun toDistributedTableName(namedAggregate: NamedAggregate, suffix: String): String {
        val context = namedAggregate.getContextAlias()
            .substringBeforeLast(SERVICE_NAME_SUFFIX)
            .replace("-", "_")
        return "${context}_${namedAggregate.aggregateName}_$suffix"
    }

    private companion object {
        const val SERVICE_NAME_SUFFIX: String = "-service"
    }
}
