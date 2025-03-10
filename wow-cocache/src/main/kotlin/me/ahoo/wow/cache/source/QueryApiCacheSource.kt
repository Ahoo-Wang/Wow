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

package me.ahoo.wow.cache.source

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.apiclient.query.ReactiveSnapshotQueryApi
import me.ahoo.wow.cache.StateToCacheDataConverter
import reactor.core.publisher.Mono

interface QueryApiCacheSource<S : Any> :
    ReactiveSnapshotQueryApi<S>,
    StateCacheSource<String, MaterializedSnapshot<S>, S> {
    override val stateToCacheDataConverter: StateToCacheDataConverter<MaterializedSnapshot<S>, S>
        get() {
            return StateToCacheDataConverter {
                it.state
            }
        }

    override fun loadState(key: String): Mono<MaterializedSnapshot<S>> {
        return getById(key)
    }
}
