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

/**
 * The command line as the real commander parses it: what a usage error,
 * `--help` and `--version` exit with. test/cli/program.test.ts checks the
 * options the program declares, against a mocked commander.
 */

import { Command } from 'commander';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import packageJson from '../../package.json';
import { EXIT_CODES } from '../../src/api/errors';
import { runCLI, setupCLI } from '../../src/cli/program';
import { generateAction } from '../../src/cli/runGenerate';

vi.mock('../../src/cli/runGenerate', () => ({ generateAction: vi.fn() }));

let exitCode: typeof process.exitCode;
beforeEach(() => {
  exitCode = process.exitCode;
  process.exitCode = undefined;
  vi.clearAllMocks();
});
afterEach(() => {
  process.exitCode = exitCode;
});

/** Runs the real program on a command line, capturing what it prints. */
async function run(...args: string[]) {
  const out: string[] = [];
  const err: string[] = [];
  const program = setupCLI(
    new Command().configureOutput({
      writeOut: text => out.push(text),
      writeErr: text => err.push(text),
    }),
  );
  await runCLI(args, program);
  const code = process.exitCode;
  process.exitCode = undefined;
  return { code, out: out.join(''), err: err.join('') };
}

describe('the command line', () => {
  it('exits with the input code when the required -i is missing', async () => {
    const result = await run('generate');

    expect(result.code).toBe(EXIT_CODES.input);
    expect(result.err).toContain(
      "required option '-i, --input <file>' not specified",
    );
    expect(generateAction).not.toHaveBeenCalled();
  });

  it('exits with the input code on an unknown option', async () => {
    const result = await run('generate', '-i', 'x.json', '--bogus');

    expect(result.code).toBe(EXIT_CODES.input);
    expect(result.err).toContain("unknown option '--bogus'");
    expect(generateAction).not.toHaveBeenCalled();
  });

  it('exits with the input code when an option lacks its value', async () => {
    const result = await run('generate', '-i');

    expect(result.code).toBe(EXIT_CODES.input);
    expect(result.err).toContain(
      "option '-i, --input <file>' argument missing",
    );
  });

  it('exits with the input code on an unknown command', async () => {
    const result = await run('bogus');

    expect(result.code).toBe(EXIT_CODES.input);
    expect(result.err).toContain("unknown command 'bogus'");
  });

  it('exits with the input code, after the help, when no command is given', async () => {
    const result = await run();

    expect(result.code).toBe(EXIT_CODES.input);
    expect(result.err).toContain('Usage: wow-generator');
  });

  it('exits with 0 after --help', async () => {
    const result = await run('--help');

    expect(result.code).toBe(EXIT_CODES.success);
    expect(result.out).toContain('Usage: wow-generator');
  });

  it('exits with 0 after the help of a command', async () => {
    for (const args of [
      ['generate', '--help'],
      ['help', 'generate'],
    ]) {
      const result = await run(...args);

      expect(result.code, args.join(' ')).toBe(EXIT_CODES.success);
      expect(result.out).toContain('Usage: wow-generator generate');
    }
  });

  it('exits with 0 after --version', async () => {
    const result = await run('--version');

    expect(result.code).toBe(EXIT_CODES.success);
    expect(result.out).toBe(`${packageJson.version}\n`);
  });

  it('hands a valid command line to the generate action', async () => {
    const result = await run('generate', '-i', 'x.json', '--strict');

    expect(result.code).toBeUndefined();
    expect(generateAction).toHaveBeenCalledWith(
      expect.objectContaining({
        input: 'x.json',
        output: 'src/generated',
        strict: true,
      }),
      expect.anything(),
    );
  });

  it('lets a failure that is not commander’s propagate', async () => {
    const failure = new Error('boom');
    vi.mocked(generateAction).mockRejectedValueOnce(failure);

    await expect(
      runCLI(['generate', '-i', 'x.json'], setupCLI(new Command())),
    ).rejects.toBe(failure);
  });
});
