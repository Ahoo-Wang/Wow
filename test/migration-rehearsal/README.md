# Migration rehearsal

The blue-green rehearsal of roadmap 8c.1 (design [07 §3](../../documentation/designs/v10/07-migration.md), decision D32). It runs one complete 9.x → V10 cut-over of the 9.x example, then rolls it back twice: once before V10 has written, and once after.

- **`src/main/kotlin/.../example`** is the 9.x example's order and cart ported to V10, in context `example`. The commands and events use the names [`rehearsal/migration-map.json`](rehearsal/migration-map.json) gives them.
  - `OrderCreated` is at revision 2, with an `@Upgrade` from the 9.x shape.
  - The 9.x `OrderPayDuplicated` and `OrderOverPaid` events are retired.
  - `CartSaga.onOrderCreated` is where the 9.x compensation service's unresolved executions are parked.
  - It is a rehearsal-only host: never published, outside the coverage reports and the ABI checks.
- **`src/main/kotlin/.../driver`** drives the 9.x example-server and this host over HTTP:
  - `seed`: orders through their lifecycles, and carts;
  - `traffic`: moves orders on, fills carts, or creates new orders;
  - `await`: waits until this host holds what 9.x wrote;
  - `states`, `compare`: an order's state read on both sides, compared numbers by value.
- **[`rehearsal/rehearse.sh`](rehearsal/rehearse.sh)** runs the steps with migrate-data and writes `steps.jsonl` and `report.md` (rendered by [`rehearsal/report.py`](rehearsal/report.py)). Its header lists the steps and the environment it reads.
- **[`rehearsal/seed-compensation.js`](rehearsal/seed-compensation.js)** writes the 9.x compensation service's unresolved executions, as that service keeps them.

## Running

The heavy run is on GitHub Actions: push a branch `ci-repro/migration-rehearsal/<name>`. Settings come from `rehearsal/run.json` at the pushed commit, or from the workflow's inputs once it is on the default branch. See [`v10-migration-rehearsal.yml`](../../.github/workflows/v10-migration-rehearsal.yml).

A smoke run on a workstation, small sizes only:

```bash
./gradlew :migration-rehearsal:installDist :migrate-data:installDist
# The 9.x example-server, built from a v10-9x-baseline checkout in ../ninex: ./gradlew :example-server:installDist -x test
NINEX_HOME=../ninex/example/example-server/build/install/example-server \
V10_HOME=test/migration-rehearsal/build/install/migration-rehearsal \
MIGRATE_HOME=migration/migrate-data/build/install/migrate-data \
MANIFEST=test/migration-rehearsal/build/wow/manifest-fragment/META-INF/wow/manifest/migration-rehearsal.json \
WORK=/tmp/rehearsal ORDERS=60 DUAL_RUN_SECONDS=5 V10_SECONDS=5 \
test/migration-rehearsal/rehearsal/rehearse.sh
```

It needs Docker, JDK 21 in `JAVA_HOME` and Python 3. The stores run in containers on ports 27017 and 5432, changed with `REHEARSAL_MONGO_PORT` and `REHEARSAL_POSTGRES_PORT`. They are removed after the run unless `KEEP_STORES=true`.

## Explained differences

`compare` may differ only in the kinds `rehearse.sh` lists as explained (`EXPLAINED_KINDS`), each signed off under design 07 §3 step 4:

- `$tags`: 9.x merged the tags its extractor derived into the stored ones, so an order whose address changed kept its old province among its tags. V10's `@Tags` replaces each key it derives (decision D220). This is an intended change.
