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

package me.ahoo.wow.compensation.server.webhook.weixin.client

import me.ahoo.coapi.api.CoApi
import me.ahoo.wow.compensation.server.webhook.weixin.ConditionalOnWeiXinWebHookEnabled
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono

/**
 * CoApi's auto-configuration scans the server package and resolves the base URL of every client it finds.
 * The scan honors [ConditionalOnWeiXinWebHookEnabled], so the client is skipped when WeCom is disabled.
 * Do not register it through `@EnableCoApi`: that path skips conditions and fails when the URL is absent.
 */
@ConditionalOnWeiXinWebHookEnabled
@CoApi(baseUrl = "\${wow.compensation.webhook.weixin.url}")
interface WeiXinBotApi {

    @PostExchange
    fun sendMessage(@RequestBody sendMessage: WeiXinSendMessage): Mono<WeiXinSendResult>
}
