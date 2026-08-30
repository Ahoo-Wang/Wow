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

package me.ahoo.wow.schema.query.maskfixture

import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.MaskStrategy
import me.ahoo.wow.api.query.mask.Masking

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Masking(PrivateMaskStrategy::class)
private annotation class PrivateMask

private class PrivateMaskStrategy : MaskStrategy<PrivateMask> {
    override fun compile(annotation: PrivateMask): CompiledMask = CompiledMask { it }
}

private data class PrivateMaskStrategyState(@field:PrivateMask val secret: String)

internal fun privateMaskStrategyStateType(): Class<*> = PrivateMaskStrategyState::class.java
