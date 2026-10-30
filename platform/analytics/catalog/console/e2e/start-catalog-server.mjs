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
 * - its snapshot queries, which the memory backend serves by default from the
 *   in-memory reference query backend;
 * - from the server's test classes, `WarehouseSimulatorRoutes`: the warehouse
 *   in memory in place of ClickHouse, and `POST /testing/warehouse/tables`,
 *   which the suite puts the tables it drafts sources of through.
 *
 * With `CATALOG_E2E_COSEC=true` it runs in its `cosec` profile instead, as a
 * deployment does behind the CoSec gateway: CoSec's gateway mode, which reads
 * the caller from the `CoSec-Identity` header the gateway forwards, holds no
 * secret and ignores the bearer token. The server then listens on
 * `CATALOG_E2E_SERVICE_PORT` (18185), and a stand-in of the gateway
 * (`e2e/cosec-gateway.mjs`) on `CATALOG_E2E_PORT` in front of it, verifying
 * the suite's tokens with `CATALOG_E2E_COSEC_SECRET`.
 *
 * Build them first, from the repository root, with JDK 21:
 *
 *   ./gradlew :analytics-catalog-server:installDist :analytics-catalog-server:testClasses
 */

import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { delimiter, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startCoSecGateway } from "./cosec-gateway.mjs";

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
const cosec = process.env.CATALOG_E2E_COSEC === "true";
/** Where the server listens: behind the gateway stand-in in the `cosec` profile. */
const servicePort = cosec
  ? (process.env.CATALOG_E2E_SERVICE_PORT ?? "18185")
  : port;

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

if (cosec)
  startCoSecGateway({
    port,
    upstream: servicePort,
    secret: process.env.CATALOG_E2E_COSEC_SECRET,
  });

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
    `--server.port=${servicePort}`,
    "--wow.internal.port=0",
    "--wow.internal.host=127.0.0.1",
    "--wow.log.partitions=2",
    "--wow.subscription.checkpoint-interval=100ms",
    "--wow.analytics.catalog.refresh-interval=500ms",
    "--wow.analytics.catalog.datasets.refresh-interval=200ms",
    ...(cosec
      ? // A deployment's callers: the identity the CoSec gateway forwards.
        ["--spring.profiles.active=cosec"]
      : // The console names its caller in headers, which the server reads in development alone (a local run).
        ["--spring.profiles.active=dev"]),
    `--spring.main.sources=${SOURCES.join(",")}`,
    `--spring.web.resources.static-locations=${new URL(`file://${CONSOLE}`).href}`,
  ],
  { stdio: "inherit" },
);

// Whoever stops this process stops the JVM with it: no server outlives the run.
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"])
  process.on(signal, () => server.kill("SIGTERM"));
server.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
