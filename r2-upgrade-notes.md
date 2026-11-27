# Round two, the upgrade path: notes

## Resumed 2026-11-27 (on `main` `8c80242397`, merged into this branch)

The review half is done. Tests run on JDK 25 (`25.0.4.1+1-LTS`), toolchain 21, on an Apple M4. No measurements were taken.

**Filed (`bug`), each with its test on this branch:**
- #7229 (finding 1): an unknown `Misfire` blocks the aggregate's load on the older node. `R2UnknownMisfireTest` (kernel, the log's replay) and `R2UnknownMisfireSnapshotTest` (postgres `decodeSchedules`, mongo `MongoRules.of`): 3 of 3 fail. By reading: the Elasticsearch snapshot, `TimersSubscription.kt:87`, typed snapshot query rows.
- #7230 (finding 3, wider than the notes had it): a MongoDB log needs `insert` and `update` on `wow_log_aggregate` at every open (`createSchema`'s upsert, under every `ddl`), before `record`'s insert-first is ever reached. `R2LogRegistryGrantTest` (auth-enabled `mongod`): 4 tests, the control passes, 3 fail (the page's `insert, find`; read only; `find, update`).
- #7232 (finding 4, the defect half): under `fail`, a read stopped by a gap on a recreated topic resumes by itself once the new partition reaches the checkpoint. `R2RecreatedTopicTest`: 1 of 2 fails. The other passes and holds the design doubt (a first read after the new topic is already longer counts no gap).

**Summary issue:** "Deep review r2: the upgrade path" (findings 2, 5, 6, the doubts, the reads below, D487 ① third item). The label comment is on it.

**The reads the pause left, now done:**
- PostgreSQL `validate`, #6764's wording ("refused at start"): the record is written or refused in `PostgresLogCatalog.ensureRecord` (`:180-198`). At start that happens only through the host's events-index start (`HostQueries.kt:310-338` → `PostgresEventQueries.prepare` → `createTable`), so only for a type whose events queries this store serves. Otherwise it happens at the type's first append or read. Upgrades:137 needs that qualification.
- CoSec 8.0.0 `ProblemRenderer` (`cosec-runtime-8.0.0.jar`, read with `javap`): annotated `@CoSecSpi`, not experimental. Upgrades:222 ("experimental in CoSec 8, so its shape may change with CoSec's release candidate") is out of date: word it on `wow-cosec`'s own `@ExperimentalWowApi`.
- Checkpoints', descriptors', springdoc's and the TS clients' enum reads: see the summary issue's "Checked and held".

## The notes at the pause (2026-11-26, about 12:30 UTC)

Lane paused by the owner's word. Nothing is filed, pushed or opened. This file and the review test are committed on the
local branch `review/r2-upgrade-path` (cut from `origin/main` `0cb86d147cd4785a13e822d00067a6fd51cbeab3`), unpushed.

Range: `v10.0.0-beta.21` (`5b8e0764fb`) to `0cb86d147c` (#7208): 227 first-parent squash commits, one PR each.

**Working data** (regenerable; kept outside the repo in the shared notes folder,
`side-session-notes/local_f5178980-…/r2-upgrade-path/`):
- `prlist.txt` (PR number, merge commit), `labels.json` (per PR: label, title rule, body rule, ABI/API/surface lines
  removed, computed with `.github/scripts/breaking-label.mjs`'s own functions over the local diff);
- `breaking-sections.md`, `upgrades-sections.md` (every PR's `## Breaking` and `## Upgrades paragraph`);
- `paragraphs.json` / `paragraphs-en.md`: the Upgrades paragraphs of the 78 PRs after `2612da0046` (#7015, the end of
  #7017's roll-up), split into English and Chinese; 68 carry text, 10 say "None" or "Nothing changes";
- `tools/`: `labels.mjs`, `section.mjs`, `extract.mjs` (run with `node <tool> <repo> <dir>` / `<dir> ...`).

## Part 2, the labels (complete for the range; not yet sent)

**Extended 2026-11-27 to `8c80242397` (240 pull requests; the table as sent is the summary issue's label comment).** The 227 labels above were read again and none has changed. Of the 13 since `0cb86d147c`: #7207, #7210, #7215, #7216, #7218 and #7219 carry `!` and the label, kept. #7209, #7217, #7220, #7222, #7225, #7227 and #7228 are unlabelled and not breaking (#7217 corrects wrong answers, #7220 turns an NPE into the definition's refusal). Nothing to add from the new range.

95 of 227 carry `breaking-change`: 84 by a `!` title (all read as self-declared and kept), 11 without any signal.

**Labelled but not breaking: remove the label (11).** None has a `!` title or a removed ABI/API/surface line; each was
labelled by the old labeler (before #7147) on a body that says "None. …" or "None of its own". The nine corrected by
hand (#7080 … #7136) were all later; these were missed:
- #6753 (log holds a decision's events as returned), #6769 (an added topic partition read from its beginning),
  #6775 (duplicate at COMMITTED through a topic), #6777 (decide's own cancellation fails the command), #6784 (a
  kernel port's failure fails the command, not a rejection), #6788 (a signalled wait answers what it was told), #6800
  (a technical failure is not an inherited outcome), #6801 (a problem names the failure by class), #6804 (a backfill
  with parked aggregates is not caught up), #6805 (a saga's own mistakes unrecoverable): `## Breaking` "None. …".
- #6766 (docs only, `docs(guide)`): "None of its own: the pull request below holds the code" (#6765, labelled).
- Borderline among them: #6777 and #6784 change an answer from the caller's rejection to `wow.command.failed`; not a
  request once answered now refused, so removal still stands.

#7119 (unlabelled by hand): agree. Its "None in the API reports…" would label under the rule, but the one behaviour
change (an `end` of another reason) meets no server that exists.

**Breaking by the rule but not labelled.** Evidence in each PR's body (quoted in `breaking-sections.md`/the bodies):
- Strong (a request answered now refused, or a start now refused):
  - #6823: a path segment with a bare `;` (`c-1;`) was routed to `c-1`, now `400 wow.request.malformed`.
  - #6821: a `Wow-Wait` plan past 4,096 characters is refused before any hop is read (32 hops was already the cap).
  - #6864: a node whose handler of another context's events names no aggregate (unresolved peer source) refuses to start.
  - #6868: `@OnEvent(aggregates)` naming an aggregate its own context does not have refuses the start; a handler that
    names own-context aggregates now reads those alone (it read every aggregate of its state type).
  - #7012: a computed value past a double's range was answered (PostgreSQL `numeric`), now `400 wow.query.out-of-range`;
    answers at a decimal's edge change (`amount * 3 > 0.3` matches 0.1).
  - #7174: a log written outside Wow (no `AccessStamps`/`AccessWalks`, both `@InternalWowApi`) serving a type's events
    model refuses the start; the "writing a storage backend" page leads a user there.
  - #6998: a state's query fields follow Jackson for a base class's private annotated fields (`state.who` where it was
    `state.owner`, a `@JsonIgnore` field no longer listed): a query naming the old field is refused (not run, by reading).
- Format changed in place: #6798 (every derived call id's value changes: the aggregate id leaves the derivation);
  #6952 (a record command's `idProperty` follows `@JsonProperty`: Manifest/contract, clients regenerate); #6992 (ops
  ledger resolutions dated, services and ops upgrade together).
- Configuration or build now refuses: #6989 (`spring.codec.max-in-memory-size` no longer read for the internal port);
  #6991 (contract check fails an id moved, builds that passed); #6876 (`@OnSchedule` in a class of no processor kind
  refused at compile time; it compiled and never ran).
- Test kit, labelled elsewhere for the same kind (#6994, #6996, #7008 are labelled): #6981, #7003, #7193 (specs that
  passed now fail as the node does).
- Published npm behaviour: #6982 (`WowViewStore` throws `RangeError` for `pageSize` < 1; `CONFLICT` where `UNAVAILABLE`),
  #6942 (`wow-client` rejects with `wow.command.unknown` where the network error was; `.`/`..` ids refused).
- Weak, listed for the coordinator only: #6780 (a creation without an id is authorized on `<aggregate>/(none)`), #6883
  (env/camelCase provider options now take effect), #7094, #6954, #6980, #7158 (span outcome `skipped` for `ok`).
- Not breaking after reading: #6999, #6977, #6924, #7122, #7015, #7013, #7088, #7080, #7100, #7127, #7149 (new key),
  #6870, #6944, #6951, #7184, #7206.

## Part 1, the review

**Findings so far (none filed):**
1. **Unknown `Misfire` blocks an aggregate's load on the older node** (the known item, now with a test).
   `Misfire` has no `UNKNOWN` and its KDoc says cases may be added in a minor (`api/.../schedule/Schedules.kt:174-185`);
   `WowEnums` fails a value it does not know (`core/spi/.../json/WowEnums.kt:136-141`). Test:
   `core/kernel/src/test/kotlin/me/ahoo/wow/kernel/review/R2UnknownMisfireTest.kt` (a log whose `ScheduleSet` names
   `misfire: "fire-once"`; asserts the older node still commits `Pay`). **Not run yet**: queued behind the batch lock,
   then stopped at the pause; compile and JDK 25 run first. Same path by reading: snapshot `schedules`
   (`PostgresSnapshotStore.decodeSchedules`, `:544-548`; MongoDB `MongoRules.of`, `MongoSnapshotStore.kt:459-460`) and
   the timers subscription (`TimersSubscription.kt:87`). The timer store already skips a rule it cannot read
   (`PostgresTimerStore.kt:59, :427`). `ScheduleRule` kinds carry the "read by every node before one writes it" promise;
   `Misfire` does not. Design choice for the issue: read unknown as opaque and leave its timer to the newer node.
2. **#7163's Upgrades paragraph states a step that cannot work**: "A record of the old spelling … parks its aggregate:
   retry it once its publisher is upgraded, or skip it." A record naming its data `payload` is refused every time it is
   read (`core/spi/.../state/StateEventJson.kt:29-30, :96`, `required(... BODY ...)`); a retry reads the same record.
   Only a skip clears it. Fix in the roll-up text (trace finding).
3. **MongoDB log registry: the refusal's remedy "or insert {…} into it" does not work** for an account without insert
   on `wow_log_aggregate`: `MongoLogCollections.record` always calls `insertOne` first (`MongoLogCollections.kt:202-213`),
   and MongoDB checks the privilege before the key (`Unauthorized` 13, held by #7127's
   `MongoTimerRegistryGrantTest`). #7127 fixed this for timers only. The page (Operate › Upgrades line 137, "or insert
   each record the refusal names") repeats it. Answers #7204 §2's doubt on #7069: #7161 did not change it.
   Test would need an auth-enabled replica set; trace so far.
4. **Recreating a state topic under carried-over checkpoints can skip records silently.** A subscription's offset past
   a recreated partition's end fails by name (`OffsetGaps.kt:54-59`, "the topic was created again or truncated"), but
   once the new topic holds as many records as the old checkpoint's offset, `resolve` takes it as valid
   (`OffsetGaps.kt:77-79`) and reads on from there, skipping the first records. The page's rewritten Kafka steps must
   order it: publisher quiet, consumers caught up, topic recreated, consumers place their reads (with
   `wow.kafka.offset-gap: earliest` once) before the publisher writes. Design doubt for the summary issue: a checkpoint
   that names the topic id would catch it.
5. **Steps missing from Operate › Upgrades** for the unlabelled breaking PRs above: #6823, #6821, #6864, #6868, #6876,
   #6998, #6798, #6780, #7193 (and #7174 has its own paragraph). To go into the roll-up and one tracking issue.
6. **Quiesce steps stated in three places, not one**: line 173 (#6820: backlog 0 and parked 0 for every saga and reactor),
   #7186's paragraph (no saga lag, no timer due within the update), "Published streams on Kafka" steps 1-4 (also progress
   1, stop commits, check twice). Roll-up gives them one section.

**Formats side by side, checked by reading** (for the summary issue's "Checked and held"; most already in #7020):
stores refuse another format number and ignore unknown fields; Kafka envelope ignores unknown header/field, refuses
another format or an unknown kind by name; Manifest readers leave out unknown enum values (`JobScope` via
`ManifestTree.kt:434`); `Recoverable` read leniently (`PostgresFailureLedger.kt:391`); `LedgerRequest` has no `UNKNOWN`
(an older service refuses an ops request of a new kind, by name); query cursor token `VERSION = 2`, unchanged since the
tag, a foreign token is `InvalidCursor` (`query/.../backend/CursorPositions.kt:134-200`); internal port and cluster
channel compare formats (#7185). beta.21 → main on a kept state topic: an old consumer refuses an empty-events record
(undecodable, parks); a new consumer parks at every schedule-only commit an old publisher skipped
(`wow.subscription.version-gap`): both by name, hence D491's recreation.

**Not yet read / not done:** checkpoints' and the descriptors' readers beyond the #7020 summary; the springdoc and TS
clients' enum reads; Postgres `validate`'s exact point of refusal for #6764's wording (started at
`PostgresLogCatalog.kt:174-199`); `ProblemRenderer`'s status in CoSec 8.0.0 (jar not in this machine's cache; word the
correction on `wow-cosec`'s own experimental marker); the `skills/wow-migrate` stale-line list (only #7204 §5 and its
Kotlin API comment so far).

## Part 3, the roll-up (planned, nothing written)

To build with a script from `paragraphs.json` plus a placement map, then hand edits; en and zh pages share line
structure (both 297 lines), so the existing paragraphs map by line.

Sections under "From a 10.0.0 pre-release", existing anchors kept (`published-streams-on-kafka`, `a-migration-from-9-x`,
`queries`, `the-test-kit`):
- *Before the rollout* (new, one place for quiesce: #6820, #6798, #7186, #6862, #7172/#7183).
- *Stores and formats*: lines 135 tail, 137 (+#7127, fix finding 3), 171, 214-220; #7096, #7138, #7161, #7171, #7150,
  #7149, #7167, #7178, #7185, #7158.
- *Configuration*: lines 139-145, 161, 163 (+ #7204: an env/camelCase key under `wow.stores.<name>` naming no option
  refuses the start), 165, 167; #7124, #7102, #7106, #7110, #7114, #7118, #7120, #7122, #7140, #7195.
- *Security*: lines 147, 149, 151, 234; #7134, #7186, #7174, #6780; **a marked place for D485 ② and ⑦-⑪**.
- *HTTP and problems*: lines 153, 155, 159; #7128, #7162, #7169, #7173, #7180, #7191, #7202, #7205, #7206, #6823, #6821.
- *Kotlin API*: lines 173, 175 (`callId` → `attemptKey`), 177-181; #7079, #7094, #7101, #7105, #7112, #7132, #7144,
  #7164, #7179, #6864, #6868, #6876, #6998.
- *TypeScript packages*: lines 157, 228, 230, 232; #7081, #7087, #7093, #7099, #7113, #7115, #7121, #7123, #7125, #7126,
  #7130, #7143, #7146 (+ cosec-fetcher takes fetcher 8 only from CoSec 8.0.0), #7170, #7176, #7203.
- *The chart*: lines 169 (+#7189), 224, 226; #7197.
- *CoSec*: #7177, #7148, then lines 183-212 re-headed for a beta.21 user (beta.21 ran CoSec 8.0.0-beta.8: the beta.9
  steps, then rc.1, then 8.0.0; beta.7/beta.5 parts for older pre-releases); line 196's 8 KB bullet replaced (16 KB since
  rc.1, #7148); line 195's `@beta` superseded (8.0.0 installs untagged); line 222's "may change with CoSec's release
  candidate" reworded.
- *Published streams on Kafka*: rewritten (not patched): every commit has a record (#7172), a subscription parks at a
  version the topic did not deliver (#7183, `wow.subscription.version-gap`), a pre-release's state topic is recreated
  (D491) with finding 4's order, #7163 (fixed per finding 2), #7194, #6862's placement.
- *A migration from 9.x*: unchanged. *Queries*: + #7095, #7139. *The test kit*: + #7193; line 288 corrected (a target
  named by a drawn id is now refused by the kernel, #7079).
- Other #7204 §2 items: #6764's "refused at start" under `validate` (verify first); "delete and rebuild" wording → sink
  generation (#7149) wherever the page says it; #7202's streaming-route 408 is already in its paragraph.

**Additions 2026-11-27, for the pull requests since `0cb86d147c` and the page's wrong lines:**
- *HTTP and problems*: #7215 (a body with no `Content-Type` is 415): **line 153's "one that names no `Content-Type` is read as before" is now wrong**, replace it. #7207 (the OpenAPI document lists the host's headers and CoSec's 429 and 503; `OpenApiOptions` gains three constructor parameters).
- *Security*: #7210 (CoSec's authorizer denies a caller that is not the authenticated principal, 403). #7216 (a model's descriptor asks the read authorizers, `QueryType.DESCRIBE`). The marked place for D485 ②⑨⑩⑫⑬ stays (②⑨ certain to add paragraphs).
- *Queries*: #7217 (PostgreSQL reads a field named `null`), #7218 (long ignore-case operands are `400 wow.query.invalid`, an unpaired surrogate is `value-mismatch`), #7219 (MongoDB refuses a metric aliased `_id`), #7220 (the analytics catalog refuses a non-ASCII digit).
- *TypeScript packages*: one more PR is to come (the TS lane's D486 ⑥).
- *Stores and formats*, line 137: MongoDB's grant is `find`, `insert` and `update` on `wow_log_aggregate` at every open, and "or insert each record the refusal names" does not work on MongoDB (#7230; also `operate/mongodb.md:199`, "only read"). PostgreSQL's "refused at start" holds only for a type whose events queries the store serves (above). Wait for #7230's fix before writing this paragraph: it decides which of the two the page says.
- *Two versions side by side*, line 17: "a reader older than its writer reads a value it does not know leniently" does not hold for `Misfire` until #7229 is fixed. The fix decides the sentence (opaque read, or a stated order).
- *Published streams on Kafka*: line 240's "a commit whose events are all retired has no record" is wrong since #7172. The rewritten section gives #7232's order: the publisher quiet, the subscribers caught up and nothing parked, the topic created again, each subscriber placed (once with `wow.kafka.offset-gap: earliest`) before the publisher writes. Then `operate/kafka.md:126` and `:133` ("the topic was created again" is an offset gap) qualified: detected only while the new partition is shorter than the checkpoint.
- *Before the rollout* (quiesce, one place): line 173's tail (`wow.subscription.backlog` 0 and `wow.subscription.parked.aggregates` 0 for every saga and reactor), #7186 ("no saga lag, and no timer due within the update"), and Kafka steps 1-4 (lines 242-245: stop commits, `progress` 1, alter partitions, check twice). One list, each source then pointing to it.
- *CoSec*: line 222 (`ProblemRenderer` is `@CoSecSpi` in 8.0.0, above), in addition to 195, 196 and 183's re-heading.
- *Text of #7163's paragraph*: "retry it once its publisher is upgraded, or skip it" becomes "skip it": `StateEventJson` refuses `payload` on every read (`StateEventJson.kt:89-99`, held by `StateEventJsonTest:179-186`), and a retry reads the same record.

Order on resume (2026-11-26's): run `R2UnknownMisfireTest` on JDK 25 (through `quiet.sh`); file findings 1-5 and the summary issue
`Deep review r2: the upgrade path`; send the label list; build the roll-up PR.
