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

package me.ahoo.wow.spi.transport

/**
 * A transport a port called could not be reached, or did not answer in time: a broker, leader or coordinator not
 * available, a network failure, too few replicas in sync, a request that timed out. The same call may succeed once the
 * transport is back.
 *
 * A transport throws it, with the client's exception as [cause], from [TopicProducer.send] and its admin's calls; a
 * subscription pauses its partition on it, as on a store that is unavailable. Any other failure stays the client's own
 * exception.
 */
class TransportUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
