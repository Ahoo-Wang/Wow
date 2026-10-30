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

package me.ahoo.wow.spring.boot

import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.dsl.countQuery
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.policy.EventsMode
import me.ahoo.wow.query.policy.QueryPolicy

const val LIBRARY = "library"
const val NO_ACCOUNT = "library.book.no-account"

/** The account a book is lent to that the command function asks the read side about wrongly: a state no type has. */
const val MISREAD_ACCOUNT = "misread"

/** The account a book is lent to whose read the read side refuses: a filter on a field the account has not. */
const val REFUSED_ACCOUNT = "refused"

/** The role that reads the library's books ([LibrarianRole]). */
const val LIBRARIAN = "librarian"

/** The account a book is lent to for which the function counts the books as the command's caller, rebuilt. */
const val COUNT_AS_CALLER = "count-as-caller"

/** The account a book is lent to for which the function counts the books as trusted code, in the command's tenant. */
const val COUNT_TRUSTED = "count-trusted"

data class LendBook(val account: String = "")

data class BookLent(val account: String)

class Book {
    var account: String = ""
}

/**
 * The binding index of a module whose model queries, written as the compiler generates it for a command function
 * that takes a [QueryApi] parameter: the model factory takes it from the host's dependencies, when the host builds the
 * models. Its fragment, `META-INF/wow/manifest/host-test-library.json`, and its service entry are under
 * `host-test-library/` of the test resources, on no host's class path but a test's own ([HostDecideQueriesTest]).
 *
 * A book is lent to a bank account the borrower sees: the command function reads the account as the command's
 * caller, and rejects the command when it reads none. It takes the node's read API both as a [QueryApi] and as a
 * [NodeQueryApi], whose trusted queries no policy restricts.
 */
class LibraryModels : ModelProvider {
    override val context: String = LIBRARY
    override val binding: Int = ModelProvider.BINDING
    override val fragment: String = "META-INF/wow/manifest/host-test-library.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> {
        val queries = dependencies.get(QueryApi::class.java, null, "the Book model")
        val node = dependencies.get(NodeQueryApi::class.java, null, "the Book model")
        return listOf(
            aggregate<Book>(LIBRARY, "book") {
                initial { Book() }
                lifecycle()
                decideSuspending<LendBook>(Creation.REQUIRED) { _, body ->
                    // The command's caller, as the command records it: its tenant, owner and space, and its operator.
                    val caller = Caller.of(command.ref.tenantId, command.scope, command.operator?.id)
                    val accounts = queries.of(caller).snapshots(Account::class.java)
                    when (body.account) {
                        MISREAD_ACCOUNT -> queries.of(caller).snapshots(String::class.java).state(body.account)
                        REFUSED_ACCOUNT -> accounts.list(listQuery { filter { "state.nothing" eq "x" } }).toList()
                        // The caller as the command records it has no role: the librarian's policy refuses it.
                        COUNT_AS_CALLER -> queries.of(caller).snapshots(Book::class.java).count(countQuery { })
                        // Trusted code reads every tenant: the function keeps to the command's itself.
                        COUNT_TRUSTED -> node.trusted().snapshots(Book::class.java)
                            .count(countQuery { filter { tenantId(command.ref.tenantId) } })
                    }
                    if (accounts.state(body.account) == null) {
                        Decision.reject(NO_ACCOUNT, "No account ${body.account} to lend the book to.")
                    } else {
                        Decision.events(BookLent(body.account))
                    }
                }
                evolve<BookLent> { account = it.account }
            },
        )
    }
}

/**
 * Only a librarian reads the library's books: a query policy by the caller's roles, as a platform service has one (the
 * ops service's `ReaderRole`). Trusted code, which has no caller, is not restricted.
 */
class LibrarianRole : QueryPolicy {
    override val events: EventsMode
        get() = EventsMode.NONE

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val who = caller.caller ?: return MatchAllFilter
        val roles = who.attributes[DevelopmentCallerResolver.ROLES_ATTRIBUTE].orEmpty()
        if (context.aggregateType.context == LIBRARY && LIBRARIAN !in roles) {
            throw QueryRequestException(
                "${who.subject} reads no book: it needs $LIBRARIAN.",
                "LIBRARIAN_REQUIRED",
                problem = FrameworkCodes.AUTH_FORBIDDEN,
            )
        }
        return MatchAllFilter
    }
}
