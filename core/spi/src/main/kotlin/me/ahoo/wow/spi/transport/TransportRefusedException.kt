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
 * A transport refused a message as it is, for good: a record beyond the broker's size limit, a topic name it does not
 * take, a record it calls invalid. Sending it again fails the same way, so it is never retried: a subscription parks
 * the aggregate at once, with this as the reason.
 *
 * A transport throws it, with the client's exception as [cause], from [TopicProducer.send]. It is an
 * [IllegalArgumentException], the failure of a value an argument carries.
 */
class TransportRefusedException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
