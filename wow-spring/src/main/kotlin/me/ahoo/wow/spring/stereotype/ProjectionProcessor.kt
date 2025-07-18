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

package me.ahoo.wow.spring.stereotype

import org.springframework.stereotype.Component

/**
 * @see org.springframework.core.annotation.MergedAnnotations
 */
@Component
@me.ahoo.wow.api.annotation.ProjectionProcessor
@Deprecated(
    message = "Use ProjectionProcessorComponent instead.",
    ReplaceWith(
        expression = "ProjectionProcessorComponent",
        imports = ["me.ahoo.wow.spring.stereotype.ProjectionProcessorComponent"]
    )
)
annotation class ProjectionProcessor

@Deprecated(
    message = "Use ProjectionProcessor instead.",
    replaceWith = ReplaceWith(
        expression = "ProjectionProcessor",
        imports = ["me.ahoo.wow.api.annotation.ProjectionProcessor"]
    )
)
@Component
@me.ahoo.wow.api.annotation.ProjectionProcessor
annotation class ProjectionProcessorComponent
