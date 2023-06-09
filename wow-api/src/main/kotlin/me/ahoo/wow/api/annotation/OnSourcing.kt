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
package me.ahoo.wow.api.annotation

import me.ahoo.wow.api.messaging.FunctionKind
import java.lang.annotation.Inherited

const val DEFAULT_ON_SOURCING_NAME = "onSourcing"

/**
 * OnSourcing .
 *
 * @author ahoo wang
 * @see me.ahoo.wow.modeling.state.StateAggregate
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
@OnMessage(FunctionKind.SOURCING, defaultFunctionName = DEFAULT_ON_SOURCING_NAME)
@MustBeDocumented
annotation class OnSourcing
