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
 * The view store service (`platform/view-store/view-store-server`) the
 * suites of test/view-store/ run against: `WOW_VIEW_STORE_URL` when
 * one is already running, else started here, in this run's own JVM, on the
 * memory backend, every port bound to 0, and stopped when the run ends.
 *
 * The view store lists views by a snapshot query; on the memory backend the
 * server's own configuration serves them from memory
 * (`wow.query.memory.enabled`, decision D209). Build it first, from the
 * repository root, with JDK 21:
 *
 *   ./gradlew :view-store-server:installDist
 *
 * It is started with one configured system view, of `SYSTEM_VIEW_DEFINITION`
 * (`viewStoreServer.ts`), as the 9.x servers are in CI.
 */

import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { TestProject } from 'vitest/node';
import { SYSTEM_VIEW_DEFINITION } from './viewStoreServer';

declare module 'vitest' {
  export interface ProvidedContext {
    /** The view store service's base URL, ending in `/`. */
    viewStoreURL: string;
  }
}

const ROOT = fileURLToPath(new URL('../../../../', import.meta.url));
const SERVER = join(ROOT, 'platform/view-store/view-store-server/build');
const MAIN = 'me.ahoo.wow.viewstore.server.ViewStoreServerKt';
/** How long the JVM may take to listen. */
const START_DEADLINE_MS = 2 * 60_000;

const BUILD = './gradlew :view-store-server:installDist';

/** The class path: the distribution's jars. */
function classPath(): string {
  const lib = join(SERVER, 'install/view-store-server/lib');
  if (!existsSync(lib))
    throw new Error(
      `The view store service is not built. From the repository root, with JDK 21: ${BUILD}` +
        ' — or set WOW_VIEW_STORE_URL to a running one.',
    );
  return join(lib, '*');
}

function java(): string {
  const home = process.env.JAVA_HOME;
  return home ? join(home, 'bin', 'java') : 'java';
}

/** Starts the service and answers its base URL once it listens. */
function start(): Promise<{ url: string; child: ChildProcess }> {
  const child = spawn(
    java(),
    [
      '-cp',
      classPath(),
      MAIN,
      '--server.address=127.0.0.1',
      '--server.port=0',
      '--wow.internal.port=0',
      // On this machine alone: a development node, which may take its callers from headers (review S-9).
      '--wow.internal.host=127.0.0.1',
      // The suites name their callers in headers, which the server trusts only when told to (a local run).
      '--wow.platform.trust-identity-headers=true',
      '--wow.view-store.system-views[0].id=conformance-system-view',
      `--wow.view-store.system-views[0].definition-id=${SYSTEM_VIEW_DEFINITION}`,
      '--wow.view-store.system-views[0].title=Conformance',
      '--wow.view-store.system-views[0].config={"kind":"record","filter":{"op":"and","children":[]},"filterMode":"simple","refresh":{"interval":null},"sort":[],"pageSize":20,"layout":"table","table":{"columns":[{"field":"id"}]},"card":{"title":"id","fields":[]}}',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );
  let log = '';
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill();
      reject(
        new Error(
          `The view store service did not listen within ${START_DEADLINE_MS / 1000} s:\n${log.slice(-4000)}`,
        ),
      );
    }, START_DEADLINE_MS);
    const read = (chunk: Buffer) => {
      log += chunk.toString();
      const port = /Netty started on port (\d+)/.exec(log)?.[1];
      if (port) {
        clearTimeout(timer);
        resolve({ url: `http://127.0.0.1:${port}/`, child });
      }
    };
    child.stdout!.on('data', read);
    child.stderr!.on('data', read);
    child.on('error', error => {
      clearTimeout(timer);
      reject(error);
    });
    child.on('exit', code => {
      clearTimeout(timer);
      reject(
        new Error(
          `The view store service exited with ${code}:\n${log.slice(-4000)}`,
        ),
      );
    });
  });
}

export default async function setup(project: TestProject): Promise<() => void> {
  const given = process.env.WOW_VIEW_STORE_URL;
  if (given) {
    project.provide('viewStoreURL', given.endsWith('/') ? given : `${given}/`);
    return () => {};
  }
  const { url, child } = await start();
  project.provide('viewStoreURL', url);
  return () => {
    child.removeAllListeners('exit');
    child.kill();
  };
}
