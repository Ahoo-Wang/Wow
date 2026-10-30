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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Where a context's log ends the history migrate-data imported from 9.x: in each partition, the log's head after
 * the latest import or tail pass, kept as a checkpoint row of the store beside the log (group [groupOf], subscription
 * [SUBSCRIPTION]), which each pass moves forward. A context that was never migrated has none.
 *
 * An entry at or before it is an imported one: 9.x already acted on it. The log's subscriptions see it so
 * (`SubscribedEntry.imported`), and the transport sink publishes it marked
 * ([me.ahoo.wow.spi.state.StateEventRecord.imported]), so a peer's saga or reactor passes over the history while its
 * projections and every sink still take it.
 */
@InternalWowApi
object ImportCutover {
    /** The checkpoint group of [context]'s cut-over, apart from every subscription group (`<service>/<context>`). */
    fun groupOf(context: String): String = "wow.migrate/$context"

    /** The subscription name of the cut-over's rows in its group. */
    const val SUBSCRIPTION: String = "cutover"
}
