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

package me.ahoo.wow.openapi.context

import me.ahoo.wow.openapi.contract.HttpComponent

/**
 * What an [HttpComponent] is built with: the component context, which generates schemas, plus [ref] for referencing
 * another component from inside this one. Reference another component with [ref] rather than registering it with the
 * context's `parameter`, `header`, `requestBody` or `response`: [ref] builds it once per render and keeps its key
 * checked for uniqueness.
 */
interface HttpComponentContext : OpenAPIComponentContext {
    /**
     * A reference to [component] (a `$ref`, or the component itself when schemas are inlined), building it first
     * unless the render already did.
     */
    fun <T : Any> ref(component: HttpComponent<T>): T
}
