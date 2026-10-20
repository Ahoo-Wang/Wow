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

package me.ahoo.wow.viewstore.domain.preferences

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesSet

/**
 * One owner's preferences for one definition in one application. Its id is derived from the three
 * ([ViewPreferencesIds]).
 */
class ViewPreferencesState(val id: String) {
    var definitionId: String = ""
        private set
    var appId: String = ""
        private set
    var order: List<String> = emptyList()
        private set
    var defaultInstanceId: String? = null
        private set
    var autoRun: Boolean? = null
        private set
    var lastTabs: Map<String, String>? = null
        private set

    @OnSourcing
    fun onSet(event: ViewPreferencesSet) {
        definitionId = event.definitionId
        appId = event.appId
        order = event.order
        defaultInstanceId = event.defaultInstanceId
        autoRun = event.autoRun
        lastTabs = event.lastTabs
    }
}
