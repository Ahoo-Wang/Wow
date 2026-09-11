# Compensation data and analysis (read-only dev integration)

The application adapter discovers the current Wow Schema and reuses `SnapshotQueryClient` / `EventStreamQueryClient`. It calls only Schema GET, snapshot paging POST and aggregation POST endpoints. It never invokes compensation commands or Schema refresh.

1. Make the dev API available locally:

   ```bash
   kubectl --context linyi-k8s -n dev port-forward --address 127.0.0.1 service/compensation-service 18916:80
   ```

2. Start the repository Storybook and open **View Engine → 真实 API 接入 → 补偿分析 → dev 服务联调**. The address defaults to `http://compensation-service.dev.svc.cluster.local/`; for this dev port-forward, replace it with `http://127.0.0.1:18916` and click **连接 API**. The server must permit the Storybook origin through CORS.
3. Select snapshot records or event streams. Root event COUNT counts stream batches; the `body` element scope counts individual event entries.
4. System templates come from current field capabilities. Personal view configurations use local IndexedDB; query results are not persisted. Disconnecting disposes the engine and aborts its requests.
5. Stop the port-forward with Ctrl-C when finished.

For authenticated applications, pass their existing configured `Fetcher` to `connectCompensation`; do not put credentials in a URL. The example deliberately does not store tokens or expose a login form.

A reproducible opt-in integration check compiles every advertised template, exercises a numeric expression and element scope, and validates response schemas:

```bash
COMPENSATION_DEV_URL=http://127.0.0.1:18916 \
  pnpm --filter @ahoo-wang/fetcher-view-engine exec vitest run test/compensationAnalysisConnection.test.ts
```

Without that environment variable, only local fixture HTTP tests run. Offline chart interaction coverage is in `AnalysisCharts.stories.tsx` and requires no API.

Open **View Engine → 真实 API 接入 → 补偿数据** for real snapshot records. It reuses the same connection UI and schema discovery, with server-side filtering, sorting and paging (20 rows per page). Field paths stay relative to the snapshot, such as `state.status`; `aggregateId` is the row key. No commands are exposed.

Run only the live data-view check (first page, second page, and status filtering):

```bash
COMPENSATION_DEV_URL=http://compensation-service.dev.svc.cluster.local/ \
  pnpm --filter @ahoo-wang/fetcher-view-engine exec vitest run \
  test/compensationAnalysisConnection.test.ts -t 'snapshot data'
```
