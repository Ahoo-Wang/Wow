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
 * What the services' JVM was doing when a test failed: e2e/globalSetup.ts writes its output and its GC and safepoint
 * log to files and names them, and its pid, in the environment; a failed test attaches both logs and two thread dumps
 * of the JVM taken while it still runs: `jcmd Thread.print -l` (the platform threads, the locks they hold and any
 * deadlock the JVM finds) and `jcmd Thread.dump_to_file` (every thread, the virtual ones too). The CI job uploads
 * them with the test's other results.
 */

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import type { TestInfo } from "@playwright/test";

/** The environment variables e2e/globalSetup.ts sets for the workers. */
export const ESHOP_LOCAL_PID = "ESHOP_LOCAL_PID";
export const ESHOP_LOCAL_LOG = "ESHOP_LOCAL_LOG";
export const ESHOP_LOCAL_GC_LOG = "ESHOP_LOCAL_GC_LOG";

/** How long one `jcmd` may take: a JVM that does not reach a safepoint does not answer it. */
const JCMD_TIMEOUT_MS = 20_000;

const run = promisify(execFile);

/** The JDK tool [name], from JAVA_HOME as the services' JVM is started, else from the PATH. */
export function jdkTool(name: string): string {
  const home = process.env.JAVA_HOME;
  return home ? join(home, "bin", name) : name;
}

/** Attaches what the services' JVM was doing, when [testInfo]'s test failed; never fails the hook itself. */
export async function attachServerDiagnostics(testInfo: TestInfo): Promise<void> {
  if (testInfo.status === testInfo.expectedStatus) return;
  testInfo.setTimeout(testInfo.timeout + 2 * JCMD_TIMEOUT_MS);
  const pid = process.env[ESHOP_LOCAL_PID];
  if (pid) {
    await jcmd(testInfo, "eshop-local-threads.txt", pid, ["Thread.print", "-l"]);
    const dir = await mkdtemp(join(tmpdir(), "eshop-local-dump-"));
    const dump = join(dir, "threads.txt");
    await jcmd(testInfo, "eshop-local-thread-dump.log", pid, ["Thread.dump_to_file", "-format=text", dump]);
    if (existsSync(dump)) await testInfo.attach("eshop-local-all-threads.txt", { path: dump });
  }
  for (const [name, variable] of [
    ["eshop-local.log", ESHOP_LOCAL_LOG],
    ["eshop-local-gc.log", ESHOP_LOCAL_GC_LOG],
  ]) {
    const path = process.env[variable];
    if (path && existsSync(path)) await testInfo.attach(name, { path });
  }
}

/** Runs `jcmd [pid] [command]` and attaches what it printed, or why it could not run, as [name]. */
async function jcmd(testInfo: TestInfo, name: string, pid: string, command: string[]): Promise<void> {
  let body: string;
  try {
    const { stdout, stderr } = await run(jdkTool("jcmd"), [pid, ...command], {
      timeout: JCMD_TIMEOUT_MS,
      maxBuffer: 64 * 1024 * 1024,
    });
    body = stdout + stderr;
  } catch (failure) {
    body = `jcmd ${pid} ${command.join(" ")} failed: ${String(failure)}`;
  }
  await testInfo.attach(name, { body, contentType: "text/plain" });
}
