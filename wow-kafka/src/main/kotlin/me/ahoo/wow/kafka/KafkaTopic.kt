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

package me.ahoo.wow.kafka

import me.ahoo.wow.api.modeling.NamedAggregate

//region asCommandTopic
const val COMMAND_TOPIC_SUFFIX = ".command"
fun NamedAggregate.asCommandTopic(topicPrefix: String): String {
    return "${topicPrefix}$contextName.$aggregateName$COMMAND_TOPIC_SUFFIX"
}
//endregion

//region asEventStreamTopic
const val EVENT_TOPIC_SUFFIX = ".event"
fun NamedAggregate.asEventStreamTopic(topicPrefix: String): String {
    return "${topicPrefix}$contextName.$aggregateName$EVENT_TOPIC_SUFFIX"
}
//endregion

//region asEventStreamTopic
const val SNAPSHOT_TOPIC_SUFFIX = ".snapshot"
fun NamedAggregate.asSnapshotTopic(topicPrefix: String): String {
    return "${topicPrefix}$contextName.$aggregateName$SNAPSHOT_TOPIC_SUFFIX"
}
//endregion
