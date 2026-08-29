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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.MaskStrategy
import kotlin.reflect.KClass

class MaskRule(
    val strategyType: KClass<out MaskStrategy<*>>,
    val annotation: Annotation,
    val compiled: CompiledMask,
) {
    override fun equals(other: Any?): Boolean =
        other is MaskRule && strategyType == other.strategyType && annotation == other.annotation

    override fun hashCode(): Int = 31 * strategyType.hashCode() + annotation.hashCode()
}
