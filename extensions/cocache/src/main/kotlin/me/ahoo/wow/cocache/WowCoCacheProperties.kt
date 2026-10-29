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

package me.ahoo.wow.cocache

import java.time.Duration

/**
 * wow-cocache's settings, `wow.cocache.*`: the one default of each key, and the class its configuration metadata comes
 * from. The module imports no Spring Boot, so [WowCoCacheAutoConfiguration] reads its keys from Spring's
 * `Environment`, falling back to the defaults here; the host knows the keys from the metadata this module ships.
 *
 * A reader's settings of each cache (its local copies, its negative caching, the lifetime of what it fills) are
 * CoCache's own, `cocache.caches.[wow.<context>.<aggregate>].*`.
 */
class WowCoCacheProperties {
    /**
     * The aggregate types, `<context>.<aggregate>`, whose states this service writes into their caches after each
     * commit; each must be one of the service's own, with a state its contract publishes. None when not set: a
     * state no one reads pays no write.
     */
    var publish: List<String> = emptyList()

    /**
     * How long a state this service writes lives in the cache, a deleted aggregate's tombstone too: the cache's own
     * lifetime (`cocache.caches.[wow.<context>.<aggregate>].ttl`, else CoCache's default) when not set. A reader's
     * fill lives by the reader's lifetime, so give both sides the same.
     */
    var ttl: Duration? = null

    companion object {
        const val PREFIX: String = "wow.cocache"

        /** The key of [publish]. */
        const val PUBLISH: String = "wow.cocache.publish"

        /** The key of [ttl]. */
        const val TTL: String = "wow.cocache.ttl"
    }
}
