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

package me.ahoo.wow.openapi.command

import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.Schemas.getSchemaRef

object CommandStageSchema {
    val name = CommandStage::class.java.simpleName
    val schema = StringSchema().apply {
        CommandStage.entries.forEach {
            addEnumItem(it.name)
        }
    }

    val schemaRef = CommandStage::class.java.getSchemaRef()

    val default = CommandStage.PROCESSED.name
}
