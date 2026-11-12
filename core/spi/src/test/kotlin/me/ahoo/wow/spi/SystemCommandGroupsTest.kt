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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.spi.SystemCommandGroups.Group
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test

class SystemCommandGroupsTest {
    @Test
    fun `each group should be its owner's registry`() {
        Group.LIFECYCLE.commands.assert().isSameAs(Lifecycle.COMMANDS)
        Group.SCHEDULE.commands.assert().isSameAs(Schedules.COMMANDS)
        Group.JOB.commands.assert().isSameAs(ScheduleJobs.COMMANDS)
        Group.REBUILD.commands.assert().isSameAs(SnapshotRebuilds.COMMANDS)
        Group.LEDGER.commands.assert().isSameAs(Ledger.COMMANDS)
    }

    @Test
    fun `no command should be in two groups, by its name or by its class`() {
        val names = Group.entries.flatMap { it.commands.keys }
        val classes = Group.entries.flatMap { it.commands.values }
        names.assert().doesNotHaveDuplicates()
        classes.assert().doesNotHaveDuplicates()
        SystemCommandGroups.ALL.names.assert().containsExactlyInAnyOrderElementsOf(names)
    }

    @Test
    fun `a set should name each of its commands both ways`() {
        val all = SystemCommandGroups.ALL
        all.commands.forEach { (name, type) ->
            all.classOf(name).assert().isEqualTo(type)
            all.nameOf(type).assert().isEqualTo(name)
        }
        all.nameOf(String::class.java).assert().isNull()
        all.classOf("wow.lifecycle.Unknown").assert().isNull()
    }

    @Test
    fun `each meaning should hold its groups`() {
        SystemCommandGroups.DECIDED_BY_EVERY_AGGREGATE.groups.assert()
            .containsExactlyInAnyOrder(Group.LIFECYCLE, Group.SCHEDULE)
        SystemCommandGroups.DECIDED_BY_A_SYSTEM_MODEL.groups.assert()
            .containsExactlyInAnyOrder(Group.LIFECYCLE, Group.SCHEDULE, Group.JOB, Group.REBUILD)
        SystemCommandGroups.LISTED_BY_A_CONTRACT.groups.assert()
            .containsExactlyInAnyOrder(Group.LIFECYCLE, Group.JOB, Group.REBUILD)
        SystemCommandGroups.SENT_BY_A_SAGA.groups.assert()
            .containsExactlyInAnyOrder(Group.LIFECYCLE, Group.SCHEDULE, Group.LEDGER)
        SystemCommandGroups.ALL.groups.assert().containsExactlyInAnyOrder(*Group.entries.toTypedArray())
        (TransferOwner::class.java in SystemCommandGroups.SENT_BY_A_SAGA).assert().isTrue()
        (RebuildSnapshot::class.java in SystemCommandGroups.SENT_BY_A_SAGA).assert().isFalse()
        (SnapshotRebuilds.REBUILD_SNAPSHOT in SystemCommandGroups.LISTED_BY_A_CONTRACT).assert().isTrue()
        (Schedules.SET_SCHEDULE in SystemCommandGroups.LISTED_BY_A_CONTRACT).assert().isFalse()
    }
}
