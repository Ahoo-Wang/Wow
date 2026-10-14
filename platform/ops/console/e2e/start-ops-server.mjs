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
 * The real ops server (`platform/ops/ops-server`) the end-to-end suite runs
 * against, in a JVM of its own, serving this console's build (`dist/`) as
 * the distribution serves its `ui/` folder: Playwright's `webServer` starts
 * it and stops it when the run ends. It runs on the memory backend, every
 * topic in memory, with the server's test configuration on the class path:
 *
 * - `MemorySnapshotQueries`: the in-memory reference query backend, so the
 *   snapshot query route answers on the memory backend (the eShop's DX gap
 *   G14; `wow.query.memory.enabled`, D209, replaces it once it lands);
 * - `InMemoryOpsTopics`: the command transport in place of Kafka;
 * - `LedgerSimulatorRoutes`: `POST /testing/ledger/{parked,resolved}`, the
 *   owning services' ledgers, which the suite publishes its entries through.
 *
 * Build them first, from the repository root, with JDK 21:
 *
 *   ./gradlew -Pwow.scope=v10 :ops-server:installDist :ops-server:testClasses :query:testFixturesJar
 */

import { spawn } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { delimiter, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../../../", import.meta.url));
const CONSOLE = fileURLToPath(new URL("../dist/", import.meta.url));
const SERVER = join(ROOT, "platform/ops/ops-server/build");
const MAIN = "me.ahoo.wow.ops.server.OpsServerKt";
const SOURCES = [
  "me.ahoo.wow.ops.testing.MemorySnapshotQueries",
  "me.ahoo.wow.ops.testing.InMemoryOpsTopics",
  "me.ahoo.wow.ops.testing.LedgerSimulatorRoutes",
];
const BUILD =
  "./gradlew -Pwow.scope=v10 :ops-server:installDist :ops-server:testClasses :query:testFixturesJar";

const port = process.env.OPS_E2E_PORT ?? "18188";

function classPath() {
  const lib = join(SERVER, "install/ops-server/lib");
  const testClasses = join(SERVER, "classes/kotlin/test");
  const queryLibs = join(ROOT, "query/query/build/libs");
  const fixtures = existsSync(queryLibs)
    ? readdirSync(queryLibs).find((file) => file.endsWith("-test-fixtures.jar"))
    : undefined;
  if (!existsSync(lib) || !existsSync(testClasses) || !fixtures)
    throw new Error(
      `The ops server is not built. From the repository root, with JDK 21: ${BUILD}`,
    );
  return [join(lib, "*"), testClasses, join(queryLibs, fixtures)].join(
    delimiter,
  );
}

if (!existsSync(join(CONSOLE, "index.html")))
  throw new Error(
    "The console is not built: pnpm build, in platform/ops/console.",
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
    `--spring.main.sources=${SOURCES.join(",")}`,
    `--spring.web.resources.static-locations=${new URL(`file://${CONSOLE}`).href}`,
  ],
  { stdio: "inherit" },
);

// Whoever stops this process stops the JVM with it: no server outlives the run.
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"])
  process.on(signal, () => server.kill("SIGTERM"));
server.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
