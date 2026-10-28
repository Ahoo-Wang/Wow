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

package me.ahoo.wow.spi

/**
 * A store refused a value as it is, for good: a value it cannot store (a `\u0000` PostgreSQL's `jsonb` does not
 * take, a number out of range), a document beyond its size limit, a document Elasticsearch cannot parse. Writing it
 * again fails the same way, so it is never retried: a subscription parks the aggregate at once.
 *
 * A backend throws it, with the driver's exception as [cause]. It is an [IllegalArgumentException], the failure of a
 * value an argument carries.
 */
class StorageRefusedException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
