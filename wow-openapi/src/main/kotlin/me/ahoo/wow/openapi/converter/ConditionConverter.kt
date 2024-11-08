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

package me.ahoo.wow.openapi.converter

import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Condition.Companion.EMPTY_VALUE
import me.ahoo.wow.api.query.Operator

class ConditionConverter : TargetTypeModifyConverter() {
    override val targetType: Class<*> = Condition::class.java
    override fun modify(resolvedSchema: Schema<*>): Schema<*> {
        resolvedSchema.properties[Condition::field.name]?.setDefault(EMPTY_VALUE)
        resolvedSchema.properties[Condition::operator.name]?.setDefault(Operator.ALL.name)
        resolvedSchema.properties[Condition::value.name]?.setDefault(EMPTY_VALUE)
        resolvedSchema.properties[Condition::children.name]?.setDefault(emptyList<Condition>())
        resolvedSchema.properties[Condition::options.name]?.setDefault(emptyMap<String, Any>())
        return resolvedSchema
    }
}
