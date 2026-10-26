# Migration rehearsal

The blue-green rehearsal of roadmap 8c.1 (design [07 §3](../../documentation/designs/07-migration.md), decision D32). It runs one complete 9.x → V10 cut-over of the 9.x example and of the view store the example-server embeds, then rolls it back twice: once before V10 has written, and once after. Two V10 hosts take over, both on one PostgreSQL database: this host (context `example`) and the V10 view store service (`platform/view-store`, context `view-store`).

Before that online cut-over, it runs an offline one of the same data into a database of its own, the default way (decision D267 ⑳): 9.x stops, then `import`, `compare`, `snapshot` and `backfill-tags` run and the V10 hosts start. Their sampled states must equal 9.x's from before it stopped. The report times its write stop beside the online one.

- **`src/main/kotlin/.../example`** is the 9.x example's order and cart ported to V10, in context `example`. The commands and events use the names [`rehearsal/migration-map.json`](rehearsal/migration-map.json) gives them.
  - `OrderCreated` is at revision 2, with an `@Upgrade` from the 9.x shape.
  - The 9.x `OrderPayDuplicated` and `OrderOverPaid` events are retired.
  - `CartSaga.onOrderCreated` is where the 9.x compensation service's unresolved executions are parked.
  - It is a rehearsal-only host: never published, outside the coverage reports and the ABI checks.
- **`src/main/kotlin/.../driver`** drives the 9.x example-server and this host over HTTP:
  - `seed`: orders through their lifecycles, and carts;
  - `traffic`: moves orders on, fills carts, or creates new orders;
  - `await`: waits until this host holds what 9.x wrote;
  - `states`, `compare`: an order's state read on both sides, compared numbers by value;
  - `views …` (`ViewsDriver`): the view store's part. `seed` creates views in 9.x and takes them through saves, renames, shares, claims and deletions, with each user's preferences and each tenant's dataset wordings; `traffic` renames views, or creates some; `await` and `states` read them on V10 (its state routes) and on 9.x (its snapshot queries, since the 9.x view store closes its state routes); `verify` renames in 9.x, after the rollback, every view V10 renamed, on the version V10 left.
- **[`rehearsal/rehearse.sh`](rehearsal/rehearse.sh)** runs the steps with migrate-data and writes `steps.jsonl` and `report.md` (rendered by [`rehearsal/report.py`](rehearsal/report.py)). Its header lists the steps and the environment it reads.
  - Through the dual-run window the V10 host runs on standby (`wow.node.standby`): it serves reads, is ready, and refuses commands with `wow.node.standby`. At the cut-over, after the final pass, the script resumes it on its internal port as a node of its service (`Wow-Service`), as an operator would (deployment run book, "Standby for a migration's cut-over").
- **[`rehearsal/migration-map.json`](rehearsal/migration-map.json)** maps both: the order and the cart, and the view store's `view`, `view_preferences` and `dataset_wording`. A 9.x view's `ViewAudienceChanged` (a share or a claim, a 9.x owner transfer) imports as V10's history-only event of that name, with the owner it moved the view to (`"owner": "toOwnerId"`). The 9.x deletion of a view is V10's `AggregateDeleted`; that of a dataset wording is V10's `DatasetWordingCleared`.
- **[`rehearsal/seed-compensation.js`](rehearsal/seed-compensation.js)** writes the 9.x compensation service's unresolved executions, as that service keeps them.

## Running

The heavy run is on GitHub Actions: push a branch `ci-repro/migration-rehearsal/<name>`. Settings come from `rehearsal/run.json` at the pushed commit, or from the workflow's inputs once it is on the default branch. See [`migration-rehearsal.yml`](../../.github/workflows/migration-rehearsal.yml).

A smoke run on a workstation, small sizes only:

```bash
./gradlew :migration-rehearsal:installDist :view-store-server:installDist :migrate-data:installDist
# The 9.x example-server, built from a v10-9x-baseline checkout in ../ninex: ./gradlew :example-server:installDist -x test
NINEX_HOME=../ninex/example/example-server/build/install/example-server \
V10_HOME=test/migration-rehearsal/build/install/migration-rehearsal \
VIEWS_HOME=platform/view-store/view-store-server/build/install/view-store-server \
VIEWS_MANIFEST=platform/view-store/view-store-server/manifest/baseline.json \
MIGRATE_HOME=migration/migrate-data/build/install/migrate-data \
MANIFEST=test/migration-rehearsal/build/wow/manifest-fragment/META-INF/wow/manifest/migration-rehearsal.json \
WORK=/tmp/rehearsal ORDERS=60 DUAL_RUN_SECONDS=5 V10_SECONDS=5 \
test/migration-rehearsal/rehearsal/rehearse.sh
```

It needs Docker, JDK 21 in `JAVA_HOME` and Python 3. The stores run in containers on ports 27017 and 5432, changed with `REHEARSAL_MONGO_PORT` and `REHEARSAL_POSTGRES_PORT`. They are removed after the run unless `KEEP_STORES=true`.

## Explained differences

`compare` may differ only in what `rehearse.sh` lists as explained for each aggregate (`EXPLAINED_KINDS`), each signed off under design 07 §3 step 4:

- `example-service.order`, `$tags`: 9.x merged the tags its extractor derived into the stored ones, so an order whose address changed kept its old province among its tags. V10's `@Tags` replaces each key it derives (decision D220). This is an intended change.
- `view-store.dataset_wording`, any field of a wording 9.x deleted (`@deleted`): 9.x's `ClearDatasetWording` was a `DeleteAggregate`, which kept the wording's last values and let it never be set again. V10 clears it (`DatasetWordingCleared`), keeps it, and sets it again. This is an intended change.
