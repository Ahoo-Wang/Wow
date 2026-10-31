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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.manifest.AggregateIdSource
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.wire.CallWires
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The HTTP command client (decision D257) against a fake peer: its two modes, its problems and its retries. */
class HttpCommandClientTest {
    data class DeliverItems(val items: List<String>)

    /** A command whose body names its box (`@AggregateId`); a creation's may name none. */
    data class Restock(val boxId: String?, val quantity: Int)

    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "a b")
    private val alice = Caller.of(
        tenantId = "t1",
        scope = Scope("alice"),
        subject = "alice",
        attributes = mapOf(Caller.APP to listOf("console")),
    )

    private fun client(
        mode: ClientMode = ClientMode.Service(SERVICE),
        retry: RetryPolicy = RetryPolicy.of(backoff = 1.milliseconds),
        endpoints: EndpointResolver = EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)),
    ) = WowClient.builder()
        .endpoints(endpoints)
        .apply {
            when (mode) {
                is ClientMode.Service -> service(checkNotNull(mode.service))
                is ClientMode.User -> user(mode.credential)
            }
        }
        .retry(retry)
        .contracts(contractOf(BOX, DeliverItems::class.java))
        .build()
        .commands

    private fun committed(status: CommandResult.Status = CommandResult.Status.SUCCEEDED, error: ErrorInfo? = null) =
        resultOf(ref, status, error)

    @Test
    fun `a service-mode command should go to the internal port as the service, asserting the caller as its root`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()))
            val result = client().of(
                alice
            ).wait(CommandRequest.builder(ref, DeliverItems(listOf("mug"))).wait(WaitPlan.DEFAULT).build())
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val request = peer.taken.single()
            request.method.assert().isEqualTo("POST")
            request.path.assert().isEqualTo("/mail/box/a%20b/deliver-items")
            request.headers[WowHeaders.SERVICE].assert().isEqualTo(SERVICE)
            request.headers[WowHeaders.REQUEST_ID].assert().isNotBlank()
            request.headers.containsKey(WowHeaders.WAIT).assert().isFalse()
            WowJson.mapper.readTree(request.body)["items"][0].asString().assert().isEqualTo("mug")
            val root = CallWires.decode(request.headers.getValue(WowHeaders.CALL))
            root.tenantId.assert().isEqualTo("t1")
            Scope(root.ownerId, root.spaceId).assert().isEqualTo(Scope("alice"))
            root.operator.assert().isEqualTo(Operator("alice", "t1", false))
            root.facts.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
            root.causation.correlationId.assert().isEqualTo(root.commandId)
        }

    @Test
    fun `a request's type should come from the contracts, its tenant be the caller's unless it targets another`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()), Reply.json(committed()), Reply.json(committed()))
            client().of(alice).wait(DeliverItems(listOf("mug")), "a b")
            // A creation names no id: the peer assigns it.
            client().of(alice).wait(DeliverItems(emptyList()))
            // A ref of another tenant targets it, acting elsewhere, as the in-process client does.
            client().of(alice).wait(CommandRequest.to(BOX.ref("t2", "x"), DeliverItems(emptyList())))
            peer.taken.map { it.path }.assert()
                .containsExactly(
                    "/mail/box/a%20b/deliver-items",
                    "/mail/box/deliver-items",
                    "/mail/box/x/deliver-items"
                )
            val roots = peer.taken.map { CallWires.decode(it.headers.getValue(WowHeaders.CALL)) }
            roots.map { it.tenantId }.assert().containsExactly("t1", "t1", "t2")
            roots.map { it.operator!!.crossScope }.assert().containsExactly(false, false, true)
            caught<Misuse.Argument> { runBlocking { client().wait("no command of a contract") } }
                .message.assert().contains("decided by no aggregate")
            caught<Misuse.Argument> { runBlocking { client().wait(DeleteAggregate, "a b") } }
                .message.assert().contains("lifecycle command")
        }

    @Test
    fun `a command by no id to an aggregate whose id is its owner's should go to the caller's own`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()), Reply.json(committed()))
            val owned = contractOf(BOX, DeliverItems::class.java).map { fragment ->
                fragment.copyWith(
                    manifest = fragment.manifest.copyWith(
                        contexts = fragment.manifest.contexts.map { context ->
                            context.copyWith(
                                aggregates = context.aggregates.map { aggregate ->
                                    aggregate.copyWith(
                                        idSource = AggregateIdSource.OWNER,
                                        commands = aggregate.commands.map {
                                            it.copyWith(
                                                creation = Creation.NONE,
                                                routes = CommandRoutes.of("mail", "box", it.name, Creation.NONE, true),
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    ),
                )
            }
            val client = WowClient.builder()
                .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
                .service(SERVICE)
                .contracts(owned)
                .build()
                .commands
                .of(alice)
            client.wait(DeliverItems(listOf("mug")))
            // A trusted caller still names the id.
            client.wait(DeliverItems(listOf("cup")), "alice")
            peer.taken.map { it.path }.assert().containsExactly(
                "/mail/box/deliver-items",
                "/mail/box/alice/deliver-items"
            )
            val root = CallWires.decode(peer.taken.first().headers.getValue(WowHeaders.CALL))
            root.ownerId.assert().isEqualTo("alice")
        }

    @Test
    fun `a command whose body names its aggregate should go to its route without an id, its body naming the ref's`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()), Reply.json(committed()), Reply.json(committed()))
            val client = WowClient.builder()
                .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
                .service(SERVICE)
                .contracts(contractOf(BOX, Restock::class.java, idProperty = "boxId"))
                .build()
                .commands
                .of(alice)
            client.wait(Restock("a b", 1))
            client.wait(CommandRequest.to(ref, Restock("a b", 2)))
            // A creation whose body names none is sent with the ref's id in its body, the route having none.
            client.wait(CommandRequest.to(ref, Restock(null, 3)))
            peer.taken.map { it.path }.assert().containsOnly("/mail/box/restock")
            WowJson.mapper.readTree(peer.taken.last().body)["boxId"].asString().assert().isEqualTo("a b")
            // A ref that names another box than the body is the code's mistake, never sent.
            assertThrownBy<Misuse.Argument> {
                runBlocking { client.wait(CommandRequest.to(BOX.ref("t1", "x"), Restock("a b", 1))) }
            }.hasMessageContaining("The request names the aggregate x, but the body of mail.box.Restock names a b")
            peer.taken.size.assert().isEqualTo(3)
        }

    @Test
    fun `a command sent while deciding should carry a request id derived from the deciding command`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()))
            peer.reply(Reply.json(committed()))
            withContext(DecidingCommand("deciding")) {
                client().of(alice).send(CommandRequest.to(ref, DeliverItems(listOf("mug"))))
                client().of(alice).send(CommandRequest.to(ref, DeliverItems(listOf("cup"))))
            }
            peer.taken.map { it.headers[WowHeaders.REQUEST_ID] }.assert().containsExactly(
                DecidingCommand.requestIdOf("deciding", 0),
                DecidingCommand.requestIdOf("deciding", 1),
            )
        }

    @Test
    fun `a service-mode command to another scope should assert that scope, acting elsewhere`(): Unit = runBlocking {
        peer.reply(Reply.json(committed()))
        val sent = CommandRequest.builder(ref, DeliverItems(listOf("mug")))
            .targetTenant("t2")
            .targetScope(Scope("bob"))
            .requestId("r-9")
            .expectedVersion(4)
            .build()
        client().of(alice).send(sent)
        val request = peer.taken.single()
        request.headers[WowHeaders.REQUEST_ID].assert().isEqualTo("r-9")
        request.headers[WowHeaders.IF_MATCH].assert().isEqualTo("\"4\"")
        request.headers.containsKey(WowHeaders.SCOPE).assert().isFalse()
        val root = CallWires.decode(request.headers.getValue(WowHeaders.CALL))
        root.tenantId.assert().isEqualTo("t2")
        Scope(root.ownerId, root.spaceId).assert().isEqualTo(Scope("bob"))
        root.operator!!.crossScope.assert().isTrue()
    }

    @Test
    fun `a user-mode command should go to the public routes with the forwarded credential and the wait`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()))
            val sent = CommandRequest.builder(ref, DeliverItems(emptyList()))
                .wait(WaitPlan.of(Hop.committed(), Hop.snapshot()))
                .timeout(3.seconds)
                .targetTenant("t2")
                .expectedVersion(2)
                .build()
            withContext(ForwardedHeaders(mapOf("Authorization" to "Bearer user", "CoSec-App-Id" to "console"))) {
                client(
                    ClientMode.User(UserCredential.FORWARDED)
                ).of(alice).wait(sent)
            }
            val request = peer.taken.single()
            request.headers["Authorization"].assert().isEqualTo("Bearer user")
            request.headers["CoSec-App-Id"].assert().isEqualTo("console")
            request.headers.containsKey(WowHeaders.CALL).assert().isFalse()
            request.headers[WowHeaders.WAIT].assert().isEqualTo("committed > snapshot")
            request.headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("3s")
            request.headers[WowHeaders.SCOPE].assert().isEqualTo("tenant=\"t2\", owner=\"alice\"")
            request.headers[WowHeaders.IF_MATCH].assert().isEqualTo("\"2\"")
        }

    @Test
    fun `a user-mode command with only a timeout should leave the plan to the service`(): Unit = runBlocking {
        peer.reply(Reply.json(committed()))
        val sent = CommandRequest.builder(ref, DeliverItems(emptyList())).timeout(3.seconds).build()
        sent.plan!!.isDefault.assert().isTrue()
        client(ClientMode.User(UserCredential.NONE)).of(alice).wait(sent)
        val request = peer.taken.single()
        // The default plan waits for the snapshot, or for the commit where the aggregate has none: the service knows.
        request.headers.containsKey(WowHeaders.WAIT).assert().isFalse()
        request.headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("3s")
    }

    @Test
    fun `a user-mode send should wait for the peer to accept the command`(): Unit = runBlocking {
        peer.reply(Reply.json(resultOf(ref, hop = Hop.accepted("mail")), status = 202))
        val result = client(
            ClientMode.User(UserCredential.NONE)
        ).send(CommandRequest.to(BOX.unassigned("t1"), DeliverItems(emptyList())))
        result.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        val request = peer.taken.single()
        request.path.assert().isEqualTo("/mail/box/deliver-items")
        request.headers[WowHeaders.WAIT].assert().isEqualTo("accepted")
        request.headers.containsKey(WowHeaders.WAIT_TIMEOUT).assert().isFalse()
        request.headers.containsKey("Authorization").assert().isFalse()
        // A request that waits for more fails before anything is sent.
        caught<IllegalArgumentException> {
            runBlocking { client().send(CommandRequest.of(DeliverItems(emptyList()), wait = WaitPlan.SNAPSHOT)) }
        }.message.assert().contains("use wait() for the plan snapshot")
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a rejection should be a CommandRejectedException, whether answered as a problem or a result`() {
        val rejected = committed(CommandResult.Status.REJECTED, ErrorInfo("mail.box.full", "Full."))
        peer.reply(Reply.problem(422, mapOf("code" to "mail.box.full", "result" to rejected)))
        caught<CommandRejectedException> {
            runBlocking { client().wait(CommandRequest.to(ref, DeliverItems(emptyList()))) }
        }.code.assert().isEqualTo("mail.box.full")
        peer.reply(Reply.json(rejected))
        assertThrownBy<CommandRejectedException> {
            runBlocking {
                client().wait(
                    CommandRequest.to(ref, DeliverItems(emptyList()))
                )
            }
        }
        peer.taken.assert().hasSize(2)
    }

    @Test
    fun `a failure should be a WowProblemException with the hint of its code`() {
        val failed = committed(CommandResult.Status.FAILED, ErrorInfo("wow.command.failed", "Boom."))
        peer.reply(Reply.problem(500, mapOf("code" to "wow.command.failed", "detail" to "Boom.", "result" to failed)))
        val problem =
            caught<WowProblemException> {
                runBlocking {
                    client().wait(
                        CommandRequest.to(ref, DeliverItems(emptyList()))
                    )
                }
            }
        problem.code.assert().isEqualTo("wow.command.failed")
        problem.status.assert().isEqualTo(500)
        problem.retryable.assert().isFalse()
        problem.result.assert().isEqualTo(failed)
        problem.message.assert().contains("Boom.")
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a problem that is only HTTP's should be a WowProblemException of its status`() {
        peer.reply(Reply(401, mapOf("Content-Type" to "text/plain"), "who are you".toByteArray()))
        val problem =
            caught<WowProblemException> {
                runBlocking {
                    client().wait(
                        CommandRequest.to(ref, DeliverItems(emptyList()))
                    )
                }
            }
        problem.code.assert().isEqualTo("http.401")
        problem.message.assert().contains("who are you")
    }

    @Test
    fun `a 429 should be sent again after its Retry-After, with the same request id`(): Unit = runBlocking {
        peer.reply(
            Reply.problem(429, mapOf("code" to "wow.command.busy"), retryAfter = "0"),
            Reply.json(committed()),
        )
        client().wait(
            CommandRequest.to(ref, DeliverItems(emptyList()))
        ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        peer.taken.assert().hasSize(2)
        peer.taken.map { it.headers[WowHeaders.REQUEST_ID] }.distinct().assert().hasSize(1)
        peer.taken.map { it.headers[WowHeaders.CALL] }.distinct().assert().hasSize(1)
    }

    @Test
    fun `a Retry-After longer than the policy honors should not be waited for`() {
        peer.reply(Reply.problem(429, mapOf("code" to "wow.command.busy"), retryAfter = "60"))
        val problem =
            caught<WowProblemException> {
                runBlocking {
                    client().wait(
                        CommandRequest.to(ref, DeliverItems(emptyList()))
                    )
                }
            }
        problem.retryAfter.assert().isEqualTo(60.seconds)
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a code that keeps the request id should be retried until the attempts run out`() {
        peer.reply(Reply.problem(503, mapOf("code" to "wow.command.unknown")))
        val problem =
            caught<WowProblemException> {
                runBlocking {
                    client().wait(
                        CommandRequest.to(ref, DeliverItems(emptyList()))
                    )
                }
            }
        problem.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        peer.taken.assert().hasSize(RetryPolicy.DEFAULT.maxAttempts)
        peer.taken.map { it.headers[WowHeaders.REQUEST_ID] }.distinct().assert().hasSize(1)
    }

    @Test
    fun `a proxy's 503 without a code should be retried, a 504 never`() {
        peer.reply(Reply(503), Reply.json(committed()))
        runBlocking { client().wait(CommandRequest.to(ref, DeliverItems(emptyList()))) }
        peer.taken.assert().hasSize(2)
        peer.taken.clear()
        peer.reply(Reply.problem(504, mapOf("code" to "wow.wait.timeout")))
        val problem =
            caught<WowProblemException> {
                runBlocking {
                    client().wait(
                        CommandRequest.to(ref, DeliverItems(emptyList()))
                    )
                }
            }
        problem.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a peer that is down should be tried as often as the policy says, then be wow-peer-unavailable`() {
        withRefusingPort { closed ->
            val down = EndpointResolver.of(emptyMap(), mapOf("mail" to URI.create("http://127.0.0.1:$closed")))
            val client = WowClient.builder().endpoints(down).service("basket-service").connectTimeout(200.milliseconds)
                .retry(RetryPolicy.of(maxAttempts = 2, backoff = 1.milliseconds))
                .contracts(contractOf(BOX, DeliverItems::class.java)).build().commands
            // Refused, or never answered, as the platform has it: either way the connection was not made.
            val unreachable = caught<WowProblemException> {
                runBlocking { client.wait(CommandRequest.to(ref, DeliverItems(emptyList()))) }
            }
            unreachable.code.assert().isEqualTo(FrameworkErrors.PEER_UNAVAILABLE)
            unreachable.retry.assert().isEqualTo(Retry.NEW_REQUEST)
            unreachable.cause.assert().isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun `a context without a URL or a command without a contract should not be sent`() {
        caught<Misuse.State> {
            runBlocking {
                client(
                    endpoints = EndpointResolver.NONE
                ).wait(CommandRequest.to(ref, DeliverItems(emptyList())))
            }
        }.message.assert().contains("wow.peers.mail.url")
        caught<Misuse.State> {
            runBlocking {
                client(ClientMode.User(UserCredential.NONE), endpoints = EndpointResolver.NONE)
                    .wait(CommandRequest.to(ref, DeliverItems(emptyList())))
            }
        }.message.assert().contains("wow.peers.mail.public-url")
        // A client of no identity is not built: its mode is named.
        caught<IllegalStateException> { WowClient.builder().contracts(emptyList()).build() }
            .message.assert().contains("service(name)", "user(credential)")
        peer.taken.assert().isEmpty()
    }

    data class Seal(val reason: String = "")

    @Test
    fun `a command its contract has no route or no command of should not be sent, the code's mistake`() {
        // The request names a type whose contract has no command of that class.
        caught<Misuse.Argument> { runBlocking { client().wait(CommandRequest.to(ref, "no command of a contract")) } }
            .message.assert().contains("No contract names the command java.lang.String of mail.box")
        // A command its contract routes by id alone, sent without one.
        val sealing = WowClient.builder()
            .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
            .service(SERVICE)
            .contracts(contractOf(BOX, Seal::class.java, creation = Creation.NONE))
            .build()
            .commands
        caught<Misuse.Argument> { runBlocking { sealing.wait(Seal()) } }
            .message.assert().contains("lists no route without an aggregate id")
        // A command its contract routes without an id alone, sent to an aggregate by its id.
        val unaddressed = Manifest(
            contexts = listOf(
                ContextManifest(
                    "mail",
                    aggregates = listOf(
                        AggregateManifest(
                            "box",
                            state = StateManifest(Manifest.schemaRef("mail.boxState")),
                            commands = listOf(
                                CommandManifest(
                                    "Seal",
                                    schema = Manifest.schemaRef(Seal::class.java.name),
                                    creation = Creation.ALLOWED,
                                    routes = listOf(RouteManifest("POST", "/mail/box/seal")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val bySealRoute = WowClient.builder()
            .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
            .service(SERVICE)
            .contracts(listOf(ManifestFragment("mail-api.jar", unaddressed)))
            .build()
            .commands
        caught<Misuse.Argument> { runBlocking { bySealRoute.wait(CommandRequest.to(ref, Seal())) } }
            .message.assert().contains("lists no route to an aggregate by its id")
        peer.taken.assert().isEmpty()
    }

    @Test
    fun `a 2xx answer that holds no result should fail as an answer that never came, whether it committed unknown`() {
        peer.reply(Reply(200, mapOf("Content-Type" to "application/json"), "not json".toByteArray()))
        // The wire's or the peer's fault, not the code's: a transport failure, never a Misuse nor a refusal.
        val unknown = caught<WowProblemException> {
            runBlocking { client().wait(CommandRequest.to(ref, DeliverItems(emptyList()))) }
        }
        unknown.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        unknown.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        unknown.message.assert().contains("whether the command committed is unknown")
        // In a subscription handler it is retried, with the same request id, as an unanswered command is.
        FailureClassification.DEFAULT.classify(unknown, null).assert().isEqualTo(FailureClass.RECOVERABLE)
    }

    @Test
    fun `a status, stage or kind a peer upgraded first answers should be read as UNKNOWN`(): Unit = runBlocking {
        val newer = WowJson.mapper.writeValueAsString(committed())
            .replace("\"succeeded\"", "\"deferred\"").replace("\"committed\"", "\"delivered\"")
        peer.reply(Reply(200, mapOf("Content-Type" to "application/json"), newer.toByteArray()))
        // A status this client does not know did not succeed as far as it knows: a problem naming the newer peer.
        val newerPeer = assertThrows<WowProblemException> {
            runBlocking { client().wait(CommandRequest.to(ref, DeliverItems(emptyList()))) }
        }
        newerPeer.result!!.status.assert().isEqualTo(CommandResult.Status.UNKNOWN)
        newerPeer.result!!.hop.stage.assert().isEqualTo(Stage.UNKNOWN)
        newerPeer.message!!.assert().contains("the peer is newer than this client")
        newerPeer.retryable.assert().isFalse()
        // A stream emits it as read, for the caller to see.
        peer.reply(Reply.events("id: 0\nevent: committed\ndata: $newer\n\n"))
        client(ClientMode.User(UserCredential.NONE)).stream(CommandRequest.to(ref, DeliverItems(emptyList())))
            .toList().single().status.assert().isEqualTo(CommandResult.Status.UNKNOWN)
    }

    @Test
    fun `a user-mode stream should read one result per server-sent event`(): Unit = runBlocking {
        val accepted = WowJson.mapper.writeValueAsString(resultOf(ref, hop = Hop.accepted("mail")))
        val done = WowJson.mapper.writeValueAsString(committed())
        peer.reply(
            Reply.events(
                ": hello\n\nid: 0\nevent: accepted\ndata: $accepted\n",
                "\nid: 1\nevent: committed\n",
                "data: $done\n\n"
            )
        )
        val results = client(
            ClientMode.User(UserCredential.NONE)
        ).stream(CommandRequest.to(ref, DeliverItems(emptyList()))).toList()
        results.map { it.hop.stage }.assert().containsExactly(Stage.ACCEPTED, Stage.COMMITTED)
        peer.taken.single().headers["Accept"].assert().isEqualTo("text/event-stream")
    }

    @Test
    fun `a stream answered with a problem should emit its result, or throw without one`(): Unit = runBlocking {
        val user = client(ClientMode.User(UserCredential.NONE))
        val refused = resultOf(
            ref,
            CommandResult.Status.REJECTED,
            ErrorInfo("wow.command.invalid", "No."),
            Hop.accepted()
        )
        peer.reply(Reply.problem(400, mapOf("code" to "wow.command.invalid", "result" to refused)))
        user.stream(
            CommandRequest.to(ref, DeliverItems(emptyList()))
        ).toList().single().status.assert().isEqualTo(CommandResult.Status.REJECTED)
        peer.reply(Reply.problem(403, mapOf("code" to "wow.auth.forbidden")))
        assertThrownBy<WowProblemException> {
            runBlocking {
                user.stream(
                    CommandRequest.to(ref, DeliverItems(emptyList()))
                ).toList()
            }
        }
        peer.reply(Reply.json(committed()))
        user.stream(
            CommandRequest.to(ref, DeliverItems(emptyList()))
        ).toList().single().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a service-mode stream should be the one committed result`(): Unit = runBlocking {
        peer.reply(Reply.json(committed()))
        client().stream(
            CommandRequest.to(ref, DeliverItems(emptyList()))
        ).toList().single().hop.stage.assert().isEqualTo(Stage.COMMITTED)
        peer.taken.single().headers["Accept"].assert().isEqualTo("application/json, application/problem+json")
        peer.reply(Reply.problem(403, mapOf("code" to "wow.auth.forbidden")))
        assertThrownBy<WowProblemException> {
            runBlocking {
                client().stream(
                    CommandRequest.to(ref, DeliverItems(emptyList()))
                ).toList()
            }
        }
    }

    private companion object {
        const val SERVICE = "basket-service"
    }
}
