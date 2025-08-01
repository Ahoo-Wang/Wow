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

package me.ahoo.wow.compensation.server.webhook

import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import gg.jte.resolve.ResourceCodeResolver
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

object TemplateEngine {
    private const val TEMPLATE_ROOT = "jte"
    const val TEMPLATE_SUFFIX = ".kte"
    const val EVENT_PARAM = "event"
    const val EVENT_STATE = "state"
    const val HOST = "host"
    private val engine: TemplateEngine by lazy {
        val codeResolver = ResourceCodeResolver(TEMPLATE_ROOT)
        TemplateEngine.create(codeResolver, ContentType.Plain)
    }

    fun renderTemplate(template: String, params: Map<String, Any>): String {
        val output = StringOutput()
        engine.render(template, params, output)
        return output.toString().trim()
    }

    fun render(templateName: String, params: Map<String, Any>): String {
        return renderTemplate(templateName + TEMPLATE_SUFFIX, params)
    }

    fun renderOnEvent(
        event: DomainEvent<*>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>,
        host: String
    ): String {
        val context = mapOf(
            EVENT_PARAM to event,
            EVENT_STATE to state,
            HOST to host
        )
        return render(event.name, context)
    }
}
