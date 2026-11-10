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

package me.ahoo.wow.compiler.model.symbol

import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.validate

/**
 * Whether every type this symbol names resolves, as KSP validates it for a processor that has not opted in to its new
 * features (backing fields and context parameters), as the model processor has not
 * ([me.ahoo.wow.compiler.model.ModelSymbolProcessorProvider] says why).
 */
internal fun KSNode.isValid(): Boolean = validate(enableNewFeatures = false)
