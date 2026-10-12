# Analytics demo

The analytics service end to end on one machine: a retailer's analyst wide table in ClickHouse, published as a
dataset through the registry, and four analyses shared with the tenant, read in the console. No CoSec, Kafka or Redis:
the script issues the demo's tokens with the demo's secret.

| File | Contents |
| --- | --- |
| `compose.yaml` | ClickHouse, MongoDB and the service on in-memory buses |
| `clickhouse/retail.sql` | `retail.order_lines`: two years of order lines up to today, made up in place (200,000 rows) |
| `dataset/retail.order_lines.json` | The dataset's declaration: scope (tenant, the `region` ABAC tag), field descriptions, enums, money |
| `dataset/retail.order_lines.sensitivity.json` | The sensitivity review: `member_phone` is masked |
| `views.json` | The shared analyses |
| `demo.mjs` | Publishes the dataset, shares the analyses and prints the tokens to sign in with |

## Run it

From the repository root, build the console and the service's distribution, which the image packs:

```bash
pnpm install && pnpm --filter "wow-analytics-console..." build
```

```bash
./gradlew :wow-analytics-server:installDist
```

Start the demo, then publish the dataset (the script waits for the service):

```bash
docker compose -f analytics/demo/compose.yaml up -d --build
```

```bash
node analytics/demo/demo.mjs
```

Open <http://localhost:8080/> and sign in with a token the script printed:

- **Analyst, every region**: the dataset 「零售订单明细」 and its shared analyses —
  - 「各省 GMV 前三的城市」: the top three cities of each province, by a rank Wow computes (`RANK` + `having`);
  - 「月度 GMV 较去年同期」: GMV by month, with the same month a year before as a dotted line (`PREVIOUS`);
  - 「各渠道 GMV 占比」: each channel's share;
  - 「上月 GMV」: a metric card of the last whole month against the same month last year;
- **Analyst, the east alone**: the same analyses over the east region's rows only (ABAC);
- **Admin**: the dataset management page as well — the draft, its declaration, the review and publication.

`docker compose -f analytics/demo/compose.yaml down -v` removes the demo and its data.

## What it proves

`RetailDemoTest` (`./gradlew :wow-analytics-server:integrationTest`) runs this demo's SQL, declaration and review
through the service on Testcontainers and checks each analysis against ClickHouse's own SQL: the top two cities of each
province, each month against the same month a year before, each channel's share, an east analyst reading the east
alone, and the phone masked. A change that breaks the demo fails the build.
