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

import com.fasterxml.jackson.annotation.JsonProperty

data class WeiXinSendMessage(
    @field:JsonProperty("msgtype")
    val msgType: String = DEFAULT_MSG_TYPE,
    val markdown: Markdown
) {
    companion object {
        const val DEFAULT_MSG_TYPE = "markdown"
        internal fun String.markdown(): WeiXinSendMessage {
            return WeiXinSendMessage(markdown = Markdown(this))
        }
    }

    data class Markdown(
        val content: String
    )
}


data class WeiXinSendResult(
    val errcode: Int,
    val errmsg: String
) {
    companion object {
        const val SUCCESS_CODE = 0
    }

    fun isSuccess(): Boolean {
        return errcode == SUCCESS_CODE
    }
}