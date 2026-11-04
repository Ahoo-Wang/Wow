# wow-migrate-data

Moves a 9.x application's data into Wow V10: it checks `migration-map.json`, imports the 9.x MongoDB event store into the V10 log, imports the confirmed `PrepareKey` keys and the 9.x compensation service's unresolved executions, follows what 9.x keeps writing, compares every 9.x snapshot with the state V10 rebuilds, writes the V10 snapshots, and exports what V10 wrote back to 9.x for a rollback. 9.x tags become the V10 state's access attributes, which the V10 service stamps on the imported streams itself.

```bash
./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"
```

Each release also ships it as the image `ahoowang/wow-migrate-data` (built from this module's [`Dockerfile`](Dockerfile), its entry point the command line), and [`deploy/migrate-data`](../../deploy/migrate-data/README.md) runs it on Kubernetes, next to the stores.

- The procedure, from the first check to the cut-over and the rollback: [Migrate the data](https://wow.ahoo.me/migrate/data).
- Every command, option, exit code, configuration property, map property and checkpoint file: [migrate-data CLI](https://wow.ahoo.me/reference/migrate-data).
- The design: [Migration](../../documentation/designs/07-migration.md).
