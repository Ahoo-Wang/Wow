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

package me.ahoo.wow.client

import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds

/**
 * The system name of a system command's class, as its peer's contract lists the command ([me.ahoo.wow.manifest.
 * SystemCommands]): an aggregate's lifecycle commands ([Lifecycle.COMMANDS]), its snapshot rebuild
 * ([RebuildSnapshot]), and a context's schedule job commands ([ScheduleJobs.COMMANDS]), sent to its job aggregate
 * (`<context>.schedule`). A system command is sent as any other, by its class, to the aggregate type the request names:
 * the class alone names no aggregate.
 */
internal object SystemCommandNames {
    private val NAMES: Map<Class<*>, String> = (Lifecycle.COMMANDS + ScheduleJobs.COMMANDS)
        .entries.associate { (name, type) -> type to name } + (RebuildSnapshot::class.java to SnapshotRebuilds.REBUILD_SNAPSHOT)

    /** The system name of [type], a system command's class; null for any other class. */
    fun of(type: Class<*>): String? = NAMES[type]
}
