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

import { afterEach, describe, expect, it, vi } from 'vitest';

const { runCLI } = vi.hoisted(() => ({ runCLI: vi.fn() }));

vi.mock('../../src/cli/program', async importOriginal => ({
  ...(await importOriginal<typeof import('../../src/cli/program')>()),
  runCLI,
}));

describe('the fetcher-generator bin', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('says on stderr that the name is deprecated, then runs the CLI', async () => {
    const stderr = vi
      .spyOn(process.stderr, 'write')
      .mockImplementation(() => true);
    const stdout = vi.spyOn(process.stdout, 'write');
    const { DEPRECATED_BIN_NOTICE } = await import('../../src/cli/program');

    await import('../../src/fetcherGeneratorCli');

    expect(stderr).toHaveBeenCalledWith(`${DEPRECATED_BIN_NOTICE}\n`);
    expect(stdout).not.toHaveBeenCalled();
    expect(runCLI).toHaveBeenCalledOnce();
    expect(stderr.mock.invocationCallOrder[0]).toBeLessThan(
      runCLI.mock.invocationCallOrder[0],
    );
  });
});
