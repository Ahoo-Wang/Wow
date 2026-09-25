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

import type { Command } from 'commander';
import { CommanderError, program } from 'commander';
import packageJson from '../../package.json';
import { DEFAULT_CONFIG_PATH } from '../api/configuration';
import { EXIT_CODES } from '../api/errors';
import { DEFAULT_HTTP_TIMEOUT_MS } from '../input/resources';
import { generateAction } from './runGenerate';

/** Collects the values of an option given more than once, such as `--header`. */
export function collect(value: string, previous: string[] = []): string[] {
  return [...previous, value];
}

/**
 * Sets up the CLI program with all commands and options.
 *
 * Commander does not exit the process: a usage error (a missing required
 * option, an unknown option or command, a missing argument, no command at
 * all) is thrown as a `CommanderError`, which {@link runCLI} maps to an exit
 * code.
 *
 * @param target - The program to set up; commander's global one by default.
 * Settings made on it before this call, such as `configureOutput`, reach the
 * `generate` command too.
 * @returns The configured commander program instance
 */
export function setupCLI(target: Command = program): Command {
  // Before `command()`: a subcommand copies the exit override when it is
  // created.
  target.exitOverride();
  // compat(fetcher): package.json also installs this CLI as `fetcher-generator`, the
  // name existing scripts call; drop that bin alias in v10.
  target
    .name('wow-generator')
    .description('OpenAPI Specification TypeScript code generator for Wow')
    .version(packageJson.version, '-v, --version');

  target
    .command('generate')
    .description('Generate TypeScript code from OpenAPI specification')
    .requiredOption(
      '-i, --input <file>',
      'Input OpenAPI specification file path or URL (http/https)',
    )
    .option('-o, --output <path>', 'Output directory path', 'src/generated')
    // No commander default: an omitted -c falls back to DEFAULT_CONFIG_PATH,
    // which may be absent, while a path the user named has to exist.
    .option(
      '-c, --config <file>',
      `Configuration file path (default: ${DEFAULT_CONFIG_PATH})`,
    )
    .option(
      '-t, --ts-config-file-path <file>',
      'TypeScript configuration file path',
    )
    .option(
      '-H, --header <header>',
      'Request header for an http(s) input or configuration, as "Name: value"; repeatable',
      collect,
    )
    .option(
      '--timeout <ms>',
      `Milliseconds before fetching an http(s) input is abandoned (default: ${DEFAULT_HTTP_TIMEOUT_MS})`,
    )
    .option(
      '--schema-docs <mode>',
      'What model doc comments carry: "summary" (title, description, constraints) or "full" (also the JSON schema)',
      'summary',
    )
    .option('--strict', 'Exit with code 4 when the run logs a warning')
    .option('--verbose', 'Log every step, and the stack trace of a failure')
    .option('--quiet', 'Log only warnings and errors')
    .action(generateAction);

  return target;
}

/**
 * The exit code of a command line commander stopped at: 0 for `--help`,
 * `help` and `--version`; {@link EXIT_CODES.input} for a usage error, which
 * commander has already reported. A usage error is an invalid command line,
 * the same category as an invalid option value such as `--timeout abc`.
 */
export function commanderExitCode(error: CommanderError): number {
  return error.exitCode === 0 ? EXIT_CODES.success : EXIT_CODES.input;
}

/**
 * Parses the command line and runs the command it names. The `generate`
 * command sets the process exit code itself; a command line commander stops
 * at sets it to {@link commanderExitCode}.
 *
 * @param args - The arguments after the executable and script;
 * `process.argv` by default
 * @param target - The program to run; {@link setupCLI} on commander's global
 * one by default
 */
export async function runCLI(
  args?: readonly string[],
  target: Command = setupCLI(),
): Promise<void> {
  try {
    if (args === undefined) {
      await target.parseAsync();
    } else {
      await target.parseAsync(args, { from: 'user' });
    }
  } catch (error) {
    if (!(error instanceof CommanderError)) throw error;
    process.exitCode = commanderExitCode(error);
  }
}
