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

package me.ahoo.wow.client.java;

import me.ahoo.wow.api.aggregate.AggregateRef;
import me.ahoo.wow.api.aggregate.AggregateType;
import me.ahoo.wow.api.caller.Caller;
import me.ahoo.wow.api.client.CommandRejectedException;
import me.ahoo.wow.api.query.ListQuery;
import me.ahoo.wow.api.query.MatchAllFilter;
import me.ahoo.wow.api.read.AggregateState;
import me.ahoo.wow.api.read.Positioned;
import me.ahoo.wow.api.read.Snapshot;
import me.ahoo.wow.api.result.CommandResult;
import me.ahoo.wow.api.result.ErrorInfo;
import me.ahoo.wow.api.result.Hop;
import me.ahoo.wow.client.EndpointResolver;
import me.ahoo.wow.client.FakePeer;
import me.ahoo.wow.client.FakePeerKt;
import me.ahoo.wow.client.Reply;
import me.ahoo.wow.client.RetryPolicy;
import me.ahoo.wow.client.ServiceCredential;
import me.ahoo.wow.client.WowClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java facade (decisions D257, D260; review M-2) as a Java application uses it: the peer's contract on the class
 * path, the client built and called from Java, with Java types only.
 */
class JavaClientSmokeTest {
    private final FakePeer peer = new FakePeer();
    private final AggregateType parcel = new AggregateType("post", "parcel");
    private final AggregateRef ref = parcel.ref("t1", "p-1");
    private final Caller alice = Caller.of("t1");

    private final WowClient client = WowClient.builder()
        .endpoints(EndpointResolver.of(Map.<String, URI>of("post", peer.getBase()), Map.of("post", peer.getBase())))
        .service(ServiceCredential.bearer("a-service-token-of-at-least-32-chars"))
        .timeout(Duration.ofSeconds(5))
        .connectTimeout(Duration.ofSeconds(1))
        .retry(RetryPolicy.of(1, Duration.ZERO, Duration.ofMillis(1)))
        .build();
    private final JavaCommandClient commands = JavaCommandClient.of(client.getCommands()).of(alice);
    private final JavaSnapshotQueries<ParcelState> parcels =
        JavaQueryApi.of(client.getQueries()).of(alice).snapshots(ParcelState.class);

    @AfterEach
    void close() {
        peer.close();
    }

    private CommandResult result(CommandResult.Status status, ErrorInfo error) {
        return FakePeerKt.resultOf(ref, status, error, Hop.Companion.committed("post"), "c-1", "r-1");
    }

    @Test
    void commandsShouldBeSentBlockingOrAsynchronously() {
        peer.reply(Reply.Companion.json(result(CommandResult.Status.SUCCEEDED, null), 200));
        CommandResult sent = commands.sendAndWait(new SendParcel("bob"), ref);
        assertEquals(CommandResult.Status.SUCCEEDED, sent.getStatus());
        assertEquals("/post/parcel/p-1/send-parcel", peer.getTaken().get(0).getPath());

        CommandResult accepted = commands.sendAsync(new SendParcel("bob"), ref,
            JavaCommandClient.options().requestId("r-9").timeout(Duration.ofSeconds(2)).expectedVersion(1).build()).join();
        assertEquals("r-1", accepted.getRequestId());
        assertEquals("r-9", peer.getTaken().get(1).getHeaders().get("Wow-Request-Id"));
        assertEquals(CommandResult.Status.SUCCEEDED, commands.send(new SendParcel("bob"), ref).getStatus());
        assertEquals(CommandResult.Status.SUCCEEDED, commands.sendAndWaitAsync(new SendParcel("bob"), ref).join().getStatus());
        try (Stream<CommandResult> results = commands.stream(new SendParcel("bob"), ref)) {
            assertEquals(1, results.count());
        }
    }

    @Test
    void asynchronousCallsShouldCompose() {
        String view = "{\"ref\":{\"type\":{\"context\":\"post\",\"name\":\"parcel\"},\"tenantId\":\"t1\",\"id\":\"p-1\"},"
            + "\"version\":1,\"scope\":{},\"deleted\":false,\"tags\":{},\"state\":{\"to\":\"bob\"}}";
        peer.reply(
            Reply.Companion.json(result(CommandResult.Status.SUCCEEDED, null), 200),
            new Reply(200, Map.of("Content-Type", "application/json"), view.getBytes(), null, false));
        // Send, then read what it changed, then map it: one chain, no blocking until join().
        CompletableFuture<String> recipient = commands.sendAndWaitAsync(new SendParcel("bob"), ref,
                JavaCommandClient.options().timeout(Duration.ofMillis(1500)).build())
            .thenCompose(sent -> parcels.stateAsync(sent.getRef()))
            .thenApply(state -> state.getState().getTo());
        assertEquals("bob", recipient.join());
        assertEquals("1500ms", peer.getTaken().get(0).getHeaders().getOrDefault("Wow-Wait-Timeout", "1500ms"));
    }

    @Test
    void aRejectionShouldBeThrownOrCompleteTheFuture() {
        CommandResult rejected = result(CommandResult.Status.REJECTED, new ErrorInfo("post.full", "Full.", List.of(), Map.of()));
        peer.reply(Reply.Companion.problem(422, Map.of("code", "post.full", "result", rejected), null));
        CommandRejectedException thrown =
            assertThrows(CommandRejectedException.class, () -> commands.sendAndWait(new SendParcel("bob"), ref));
        assertEquals("post.full", thrown.getCode());
        CompletionException failed =
            assertThrows(CompletionException.class, () -> commands.sendAndWaitAsync(new SendParcel("bob"), ref).join());
        assertInstanceOf(CommandRejectedException.class, failed.getCause());
        // Or handled in the chain, as a typed exception.
        String handled = commands.sendAndWaitAsync(new SendParcel("bob"), ref)
            .thenApply(sent -> "sent")
            .exceptionally(error -> error.getCause() instanceof CommandRejectedException rejection ? rejection.getCode() : "?")
            .join();
        assertEquals("post.full", handled);
    }

    @Test
    void snapshotsShouldBeReadAsTheContractsStateClass() {
        String record = "{\"tenantId\":\"t1\",\"aggregateId\":\"p-1\",\"version\":1,\"deleted\":false,"
            + "\"firstEventTime\":1,\"lastEventTime\":2,\"state\":{\"to\":\"bob\"}}";
        peer.reply(new Reply(200, Map.of("Content-Type", "application/json"), ("[" + record + "]").getBytes(), null, false));
        List<Snapshot<ParcelState>> listed = parcels.list(new ListQuery(MatchAllFilter.INSTANCE));
        assertEquals("bob", listed.get(0).getState().getTo());
        assertEquals("bob", parcels.listAsync(new ListQuery(MatchAllFilter.INSTANCE)).join().get(0).getState().getTo());

        peer.reply(Reply.Companion.events("event: row\nid: x-1\ndata: " + record + "\n\n",
            "event: end\ndata: {\"reason\":\"complete\",\"resumable\":false}\n\n"));
        try (Stream<Positioned<Snapshot<ParcelState>>> rows = parcels.stream(new ListQuery(MatchAllFilter.INSTANCE))) {
            assertEquals("x-1", rows.findFirst().orElseThrow().getPosition());
        }

        String view = "{\"ref\":{\"type\":{\"context\":\"post\",\"name\":\"parcel\"},\"tenantId\":\"t1\",\"id\":\"p-1\"},"
            + "\"version\":3,\"scope\":{},\"deleted\":false,\"tags\":{},\"state\":{\"to\":\"bob\"}}";
        peer.reply(new Reply(200, Map.of("Content-Type", "application/json"), view.getBytes(), null, false));
        AggregateState<ParcelState> state = parcels.state("p-1");
        assertEquals(3, state.getVersion());
        assertEquals("bob", parcels.stateAsync(ref).join().getState().getTo());
        peer.reply(Reply.Companion.problem(404, Map.of("code", "wow.aggregate.not-found"), null));
        assertNull(parcels.state("p-2"));
        assertTrue(parcels.getType().equals(parcel));
    }
}
