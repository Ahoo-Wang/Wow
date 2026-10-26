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

/*
 * The real analytics catalog server (`platform/analytics/catalog/server`) the
 * end-to-end suite runs against, in a JVM of its own, serving this console's
 * build (`dist/`) as the distribution serves its `ui/` folder: Playwright's
 * `webServer` starts it and stops it when the run ends. It runs on the memory
 * backend with:
 *
 * - `wow.query.memory.enabled` (D209): the in-memory reference query
 *   backend serves the snapshot queries on the memory backend;
 * - from the server's test classes, `WarehouseSimulatorRoutes`: the warehouse
 *   in memory in place of ClickHouse, and `POST /testing/warehouse/tables`,
 *   which the suite puts the tables it drafts sources of through.
 *
 * With `CATALOG_E2E_COSEC_SECRET` it runs in its `cosec` profile instead, as a
 * deployment does (roadmap P.15): it verifies each request's CoSec token,
 * signed with that secret, and trusts no identity header.
 *
 * Build them first, from the repository root, with JDK 21:
 *
 *   ./gradlew :analytics-catalog-server:installDist :analytics-catalog-server:testClasses
 */

import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { delimiter, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../../../../", import.meta.url));
const CONSOLE = fileURLToPath(new URL("../dist/", import.meta.url));
const SERVER = join(ROOT, "platform/analytics/catalog/server/build");
const MAIN = "me.ahoo.wow.analytics.catalog.server.AnalyticsCatalogServerKt";
const SOURCES = [
  "me.ahoo.wow.analytics.catalog.testing.WarehouseSimulatorRoutes",
];
const BUILD =
  "./gradlew :analytics-catalog-server:installDist :analytics-catalog-server:testClasses";

const port = process.env.CATALOG_E2E_PORT ?? "18187";
const cosecSecret = process.env.CATALOG_E2E_COSEC_SECRET;

function classPath() {
  const lib = join(SERVER, "install/analytics-catalog-server/lib");
  const testClasses = join(SERVER, "classes/kotlin/test");
  if (!existsSync(lib) || !existsSync(testClasses))
    throw new Error(
      `The analytics catalog server is not built. From the repository root, with JDK 21: ${BUILD}`,
    );
  return [join(lib, "*"), testClasses].join(delimiter);
}

if (!existsSync(join(CONSOLE, "index.html")))
  throw new Error(
    "The console is not built: pnpm build, in platform/analytics/catalog/console.",
  );

const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, "bin", "java")
  : "java";

const server = spawn(
  java,
  [
    "-cp",
    classPath(),
    MAIN,
    "--server.address=127.0.0.1",
    `--server.port=${port}`,
    "--wow.internal.port=0",
    "--wow.internal.host=127.0.0.1",
    "--wow.log.partitions=2",
    "--wow.subscription.checkpoint-interval=100ms",
    "--wow.analytics.catalog.refresh-interval=500ms",
    "--wow.analytics.catalog.datasets.refresh-interval=200ms",
    "--wow.query.memory.enabled=true",
    ...(cosecSecret
      ? // A deployment's callers: CoSec's tokens, verified with the secret they are signed with.
        ["--spring.profiles.active=cosec", `--cosec.jwt.secret=${cosecSecret}`]
      : // The console names its caller in headers, which the server trusts only when told to (a local run).
        ["--wow.platform.trust-identity-headers=true"]),
    `--spring.main.sources=${SOURCES.join(",")}`,
    `--spring.web.resources.static-locations=${new URL(`file://${CONSOLE}`).href}`,
  ],
  { stdio: "inherit" },
);

// Whoever stops this process stops the JVM with it: no server outlives the run.
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"])
  process.on(signal, () => server.kill("SIGTERM"));
server.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
