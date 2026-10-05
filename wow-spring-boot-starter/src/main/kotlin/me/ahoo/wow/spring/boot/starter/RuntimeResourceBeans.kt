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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.runtime.RuntimeResource

/**
 * The runtime resource of [bean] (a store or bus bean, which tracing or metrics may have decorated), or
 * [RuntimeResource.NONE] when the bean is not a [T], such as an application's own replacement.
 */
internal inline fun <reified T : Any> runtimeResourceOf(bean: Any, resource: (T) -> RuntimeResource): RuntimeResource =
    (bean.getOriginalDelegate() as? T)?.let(resource) ?: RuntimeResource.NONE
