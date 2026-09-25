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

// Runs a command against a browser in Playwright's own Linux container, the
// image of the `playwright` version the stories resolve:
//
//   node scripts/linux-browser.mjs -- vitest run --project=visual
//
// The container serves the browsers (`playwright run-server`); the command
// runs here, with STORYBOOK_BROWSER_WS pointing Vitest at it
// (vitest.config.ts). So the pages are laid out, shaped and painted on Linux
// — the fonts, ICU and rasteriser of the image, whatever this machine is —
// which is what makes the screenshot baselines one set for every machine,
// and what reproduces a Linux-only failure from a Mac. CI runs the same
// script, so a baseline taken here is a baseline CI compares against.
//
// The image runs native: on an Apple silicon Mac that is its arm64 build,
// on CI amd64. The two drew every baseline to the same pixel (T5, checked
// both ways); forcing amd64 under emulation made the heaviest story three
// times slower. LINUX_BROWSER_CPUS caps the container (default 2).
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { createRequire } from 'node:module';

const separator = process.argv.indexOf('--');
const command = separator === -1 ? [] : process.argv.slice(separator + 1);
if (command.length === 0) {
  console.error('usage: node scripts/linux-browser.mjs -- <command...>');
  process.exit(2);
}

const require = createRequire(import.meta.url);
const { version } = JSON.parse(
  readFileSync(require.resolve('playwright/package.json'), 'utf8'),
);
const image = `mcr.microsoft.com/playwright:v${version}-noble`;
const name = `wow-storybook-browser-${process.pid}-${Date.now()}`;
const port = await freePort();
const cpus = process.env.LINUX_BROWSER_CPUS ?? '2';

const started = spawnSync(
  'docker',
  [
    'run',
    '--detach',
    '--rm',
    '--init',
    '--ipc=host',
    `--cpus=${cpus}`,
    '--name',
    name,
    '--publish',
    `127.0.0.1:${port}:3000`,
    // The server is the image's own Playwright, the same version.
    '--workdir',
    '/tmp',
    image,
    'npx',
    '--yes',
    `playwright@${version}`,
    'run-server',
    '--port',
    '3000',
    '--host',
    '0.0.0.0',
  ],
  { stdio: ['ignore', 'ignore', 'inherit'] },
);
if (started.status !== 0) process.exit(started.status ?? 1);

const stop = () => {
  spawnSync('docker', ['rm', '--force', name], { stdio: 'ignore' });
};
process.on('SIGINT', () => {
  stop();
  process.exit(130);
});
process.on('SIGTERM', () => {
  stop();
  process.exit(143);
});

try {
  await ready(port);
} catch (error) {
  spawnSync('docker', ['logs', name], { stdio: 'inherit' });
  stop();
  throw error;
}

const child = spawn(command[0], command.slice(1), {
  stdio: 'inherit',
  env: {
    ...process.env,
    STORYBOOK_BROWSER_WS: `ws://127.0.0.1:${port}/`,
  },
});
const code = await new Promise(resolve =>
  child.on('exit', (status, signal) => resolve(status ?? (signal ? 1 : 0))),
);
stop();
process.exit(code);

/** A port nothing on this machine listens on. */
function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port: found } = server.address();
      server.close(() => resolve(found));
    });
  });
}

/**
 * The server answers once `npx` has fetched Playwright and it listens: the
 * first run in a fresh container downloads the package, so allow a while.
 */
async function ready(at) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${at}/`);
      // Any HTTP answer means the server is up; it upgrades websockets only.
      if (response.status > 0) return;
    } catch {
      // Not listening yet.
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error(`The browser server in ${image} did not start in 3 minutes.`);
}
