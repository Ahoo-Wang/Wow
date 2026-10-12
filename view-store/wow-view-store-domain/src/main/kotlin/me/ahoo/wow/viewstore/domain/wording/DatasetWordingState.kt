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

package me.ahoo.wow.viewstore.domain.wording

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.viewstore.api.wording.DatasetWordingSet
import tools.jackson.databind.node.ObjectNode

/**
 * One tenant's wording over one dataset in one application. Its id is derived from the three ([DatasetWordingIds]).
 */
class DatasetWordingState(val id: String) {
    var dataset: String = ""
        private set
    var appId: String = ""
        private set
    var title: String? = null
        private set
    var recordNoun: String? = null
        private set
    var fields: ObjectNode? = null
        private set

    @OnSourcing
    fun onSet(event: DatasetWordingSet) {
        dataset = event.dataset
        appId = event.appId
        title = event.title
        recordNoun = event.recordNoun
        fields = event.fields
    }
}
