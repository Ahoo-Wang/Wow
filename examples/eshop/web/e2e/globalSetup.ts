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
 * Starts what the end-to-end suite drives:
 *
 * - the eShop's five services, in one JVM of their own (`EshopLocal`, in
 *   `:eshop-e2e`): on the memory backend, their topics in memory, every
 *   port bound to 0 and read back from the line it prints once they listen.
 *   Its class path comes from the Gradle task that writes it; from the
 *   repository root, with JDK 21:
 *
 *     ./gradlew :eshop-e2e:eshopLocalClasspath
 *
 *   The JVM stops when its standard input closes, so it ends with this
 *   process even when the teardown does not run.
 * - the UI's dev server, on a free port, its proxies pointed at those ports.
 *
 * The teardown stops both. ESHOP_WEB_URL is the UI's address for the tests. The JVM's whole output and its GC and
 * safepoint log go to files of their own, which e2e/diagnostics.ts attaches to a failed test with thread dumps of
 * the JVM, its pid and those files named in the environment.
 */

import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream, existsSync, mkdtempSync, readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";
import { ESHOP_LOCAL_GC_LOG, ESHOP_LOCAL_LOG, ESHOP_LOCAL_PID, jdkTool } from "./diagnostics";

const WEB = fileURLToPath(new URL("..", import.meta.url));
const CLASSPATH = join(WEB, "../e2e/build/eshop-local/classpath.txt");
const MAIN = "me.ahoo.wow.example.eshop.e2e.EshopLocalKt";
const READY = /eshop-local ready (\{.*\})/;
const START_DEADLINE_MS = 3 * 60_000;
const STOP_DEADLINE_MS = 20_000;
/** The last output kept for a failure's message. */
const TAIL = 6_000;

/** Starts the services, their output written to [log] and their GC and safepoints to [gcLog]; answers their public ports by context. */
function startServices(
  log: string,
  gcLog: string,
): Promise<{
  child: ChildProcess;
  ports: Record<string, number>;
}> {
  if (!existsSync(CLASSPATH))
    throw new Error(
      "The eShop services are not built. From the repository root, with JDK 21: " +
        "./gradlew :eshop-e2e:eshopLocalClasspath",
    );
  const child = spawn(
    jdkTool("java"),
    [
      "-Xmx768m",
      `-Xlog:gc,safepoint:file=${gcLog}:uptime,time`,
      "-Deshop.local.ports=random",
      "-Deshop.local.exit-on-stdin-eof=true",
      "-cp",
      readFileSync(CLASSPATH, "utf8").trim(),
      MAIN,
    ],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  const written = createWriteStream(log);
  // Both into one file: neither ends it, so a stream that closes first loses nothing of the other.
  child.stdout!.pipe(written, { end: false });
  child.stderr!.pipe(written, { end: false });
  let output = "";
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`The eShop did not start:\n${output.slice(-TAIL)}`));
    }, START_DEADLINE_MS);
    const read = (chunk: Buffer) => {
      // Only the tail is kept here, for the message: the whole output is in the log.
      output = (output + chunk.toString()).slice(-TAIL);
      const ready = READY.exec(output);
      if (ready) {
        clearTimeout(timer);
        resolve({ child, ports: JSON.parse(ready[1]) });
      }
    };
    child.stdout!.on("data", read);
    child.stderr!.on("data", read);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(
        new Error(`The eShop exited with ${code}:\n${output.slice(-TAIL)}`),
      );
    });
  });
}

export default async function globalSetup(): Promise<() => Promise<void>> {
  const logs = mkdtempSync(join(tmpdir(), "eshop-local-"));
  const log = join(logs, "eshop-local.log");
  const gcLog = join(logs, "eshop-local-gc.log");
  const { child, ports } = await startServices(log, gcLog);
  process.env[ESHOP_LOCAL_PID] = String(child.pid);
  process.env[ESHOP_LOCAL_LOG] = log;
  process.env[ESHOP_LOCAL_GC_LOG] = gcLog;
  for (const [context, port] of Object.entries(ports))
    process.env[`ESHOP_${context.toUpperCase()}_URL`] = `http://127.0.0.1:${port}`;
  const server = await createServer({
    root: WEB,
    configFile: join(WEB, "vite.config.ts"),
    logLevel: "warn",
    server: { host: "127.0.0.1", port: 0, strictPort: false },
  });
  await server.listen();
  const { port } = server.httpServer!.address() as AddressInfo;
  process.env.ESHOP_WEB_URL = `http://127.0.0.1:${port}`;
  return async () => {
    await server.close();
    child.removeAllListeners("exit");
    const exited = new Promise((resolve) => child.once("exit", resolve));
    child.stdin!.end();
    child.kill();
    // The services close in a few seconds; a JVM still running is killed.
    const stopped = await Promise.race([
      exited.then(() => true),
      new Promise((resolve) => setTimeout(() => resolve(false), STOP_DEADLINE_MS)),
    ]);
    if (!stopped) child.kill("SIGKILL");
  };
}
