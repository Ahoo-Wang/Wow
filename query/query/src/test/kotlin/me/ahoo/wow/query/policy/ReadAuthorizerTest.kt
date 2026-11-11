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

package me.ahoo.wow.query.policy

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.engine.ReadFacade
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.AccessFixtures.MANIFEST
import me.ahoo.wow.query.policy.AccessFixtures.NOTE
import me.ahoo.wow.query.policy.AccessFixtures.caller
import me.ahoo.wow.query.policy.AccessFixtures.record
import me.ahoo.wow.query.policy.AccessFixtures.state
import me.ahoo.wow.query.policy.AccessFixtures.stream
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.state.StateRead
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Who may read a model at all (D390 G): asked once per read, before anything is read, by every caller but trusted code. */
class ReadAuthorizerTest {
    private val asked = mutableListOf<Pair<String?, ReadAction>>()

    /** Lets a reader, a caller with the `reader` role, read; refuses everyone else. */
    private val readers = ReadAuthorizer { caller, read ->
        asked += caller.subject to read
        if ("reader" in caller.attributes["roles"].orEmpty()) {
            ReadAuthorization.Allowed
        } else {
            ReadAuthorization.Denied.of("${caller.subject} reads nothing here: it needs the reader role.")
        }
    }

    private val note = record(NOTE, "n-1", owner = "alice")
    private val storage = InMemoryQueryStorage().apply { save(note) }
    private val events = InMemoryEventQueryStorage({ type ->
        listOf(stream(NOTE, "n-1", emptyMap(), owner = "alice")).filter { it.ref.type == type }
    })

    private fun engine(vararg authorizers: ReadAuthorizer): QueryEngine = QueryEngine.Builder(MANIFEST)
        .snapshots(storage)
        .events(events)
        .accessPolicies(listOf(AccessFixtures.documents), AccessFixtures::aggregatesOf)
        .readAuthorizers(authorizers.toList())
        .build()

    private val reader = caller("alice", "roles" to listOf("reader"))
    private val stranger = caller("alice")

    private fun refused(read: suspend () -> Unit): QueryRequestException =
        assertThrows<QueryRequestException> { runBlocking { read() } }

    @Test
    fun `every query of every model asks first, and a refused caller reads nothing`(): Unit = runBlocking {
        val engine = engine(readers)
        val snapshots = engine.snapshots(NOTE)!!
        snapshots.list(ListQuery(MatchAllFilter, limit = 10), reader).toList().assert().hasSize(1)
        snapshots.count(CountQuery(MatchAllFilter), reader).count.assert().isEqualTo(1)
        engine.events(NOTE)!!.list(ListQuery(MatchAllFilter, limit = 10), reader).toList().assert().hasSize(1)
        asked.map { it.second }.assert().containsExactly(
            ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.LIST, QueryEntry.IN_PROCESS),
            ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.COUNT, QueryEntry.IN_PROCESS),
            ReadAction.of(NOTE, QueryModel.EVENTS, QueryType.LIST, QueryEntry.IN_PROCESS),
        )
        val denied = refused { snapshots.single(SingleQuery(MatchAllFilter), stranger) }
        denied.errorCode.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        denied.errorMsg.assert().contains("needs the reader role")
        refused { engine.events(NOTE)!!.list(ListQuery(MatchAllFilter, limit = 10), stranger).toList() }
            .errorCode.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `a state read and a trace ask before the state is loaded`(): Unit = runBlocking {
        val reads = engine(readers).reads
        var loaded = 0
        suspend fun read(caller: QueryCaller, what: StateRead) = reads.observed(NOTE, what, caller) {
            reads.read(caller, {
                loaded++
                state(note)
            }, { it })
        }
        read(reader, StateRead.LATEST).assert().isInstanceOf(ReadFacade.Read.Visible::class.java)
        refused { read(stranger, StateRead.LATEST) }.errorCode.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        refused { read(stranger, StateRead.TRACE) }
        loaded.assert().isEqualTo(1)
        asked.map { it.second.operation }.assert().containsExactly(QueryType.STATE, QueryType.STATE, QueryType.TRACE)
        asked.map { it.second.model }.distinct().assert().containsExactly(QueryModel.SNAPSHOT)
    }

    @Test
    fun `trusted code, which reads with no caller, is not asked`(): Unit = runBlocking {
        engine(readers).snapshots(NOTE)!!.list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList()
            .assert().hasSize(1)
        asked.assert().isEmpty()
    }

    @Test
    fun `the first refusal answers, and the authorizers after it are not asked`(): Unit = runBlocking {
        val app = ReadAuthorizer { caller, _ ->
            if (caller.app == null) {
                ReadAuthorization.Denied.of("The request names no application.", FrameworkCodes.QUERY_MALFORMED)
            } else {
                ReadAuthorization.Allowed
            }
        }
        val engine = engine(app, readers)
        refused { engine.snapshots(NOTE)!!.list(ListQuery(MatchAllFilter, limit = 10), reader).toList() }
            .errorCode.assert().isEqualTo(FrameworkCodes.QUERY_MALFORMED)
        asked.assert().isEmpty()
        ReadAuthorizer.allOf(emptyList()).assert().isSameAs(ReadAuthorizer.ALLOW_ALL)
        ReadAuthorizer.allOf(listOf(readers)).assert().isSameAs(readers)
    }

    @Test
    fun `allOf asks each authorizer while it allows, and a read every one allows is allowed`(): Unit = runBlocking {
        var askedFirst = 0
        val allowing = ReadAuthorizer { _, _ ->
            askedFirst++
            ReadAuthorization.Allowed
        }
        val both = ReadAuthorizer.allOf(listOf(allowing, readers))
        val read = ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.LIST, QueryEntry.HTTP)
        both.authorize(checkNotNull(reader.caller), read).assert().isSameAs(ReadAuthorization.Allowed)
        both.authorize(checkNotNull(stranger.caller), read).assert().isInstanceOf(ReadAuthorization.Denied::class.java)
        askedFirst.assert().isEqualTo(2)
        asked.map { it.second }.assert().containsExactly(read, read)
        both.toString().assert().startsWith("ReadAuthorizer.allOf(")
    }

    @Test
    fun `a read and a refusal compare by their fields`() {
        val read = ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.LIST, QueryEntry.HTTP)
        val same: Any = read
        val text: Any = read.toString()
        (read == same).assert().isTrue()
        (read == text).assert().isFalse()
        read.assert().isEqualTo(ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.LIST, QueryEntry.HTTP))
            .hasSameHashCodeAs(ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.LIST, QueryEntry.HTTP))
            .isNotEqualTo(ReadAction.of(NOTE, QueryModel.EVENTS, QueryType.LIST, QueryEntry.HTTP))
        val denied = ReadAuthorization.Denied("no")
        val itself: Any = denied
        (denied == itself).assert().isTrue()
        (denied == text).assert().isFalse()
        denied.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        val again = ReadAuthorization.Denied.of("no")
        denied.assert().isEqualTo(again).hasSameHashCodeAs(again)
            .isNotEqualTo(ReadAuthorization.Denied.of("no", FrameworkCodes.QUERY_MALFORMED))
        denied.toString().assert().contains(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `an entry nobody stated is read as in process`() {
        QueryEntry.UNSPECIFIED.admitted().assert().isEqualTo(QueryEntry.IN_PROCESS)
        QueryEntry.HTTP.admitted().assert().isEqualTo(QueryEntry.HTTP)
    }

    @Test
    fun `a refusal names a code of the framework's error table`() {
        assertThrows<IllegalArgumentException> { ReadAuthorization.Denied.of("no", "app.not-mine") }
        ReadAuthorization.Denied.of("no").code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        ReadAction.of(NOTE, QueryModel.SNAPSHOT, QueryType.STATE, QueryEntry.HTTP).toString().assert().contains("STATE")
        Caller.ANONYMOUS.app.assert().isNull()
    }
}
