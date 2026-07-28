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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.lifecycle.Lifecycle
import me.ahoo.wow.runtime.RuntimeActivity
import me.ahoo.wow.runtime.RuntimeContext

/**
 * Represents a message dispatcher that can run and dispatch messages.
 *
 * This interface combines the capabilities of running a dispatch process,
 * being closable for resource management, and having a name for identification.
 *
 * Implementations must keep construction inert: acquire resources and subscribe
 * to message sources from `prepare`/`start`, after the canonical runtime owns the
 * dispatcher. Long-lived asynchronous work must acquire a [RuntimeActivity] from
 * the provided [RuntimeContext], register intake closure with `onClose`, and
 * report terminal pipeline failures with `reportFailure`.
 */
interface MessageDispatcher :
    Lifecycle,
    Named
