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

import me.ahoo.wow.api.command.CommandMessage
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

const val DEFAULT_ON_COMMAND_NAME = "onCommand"

/**
 * OnCommand .
 *
 * @author ahoo wang
 * @see me.ahoo.wow.modeling.command.CommandAggregate
 */
@Target(AnnotationTarget.FUNCTION)
@Inherited
@OnMessage(CommandMessage::class, defaultHandlerName = DEFAULT_ON_COMMAND_NAME)
@MustBeDocumented
annotation class OnCommand(
    /**
     * The domain event returned after the execution of the command function is completed
     */
    val returns: Array<KClass<*>> = []
)
