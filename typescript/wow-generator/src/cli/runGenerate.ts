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

import { relative } from 'path';
import packageJson from '../../package.json';
import { EXIT_CODES, GeneratorError } from '../api/errors';
import { CodeGenerator } from '../pipeline/codeGenerator';
import type { Logger, LogLevel } from '../api/logger';
import { ConsoleLogger } from '../api/logger';
import type { GeneratorOptions, SchemaDocs } from '../api/options';

/**
 * Options of the `generate` command, as commander parses them.
 */
export interface GenerateCommandOptions {
  input: string;
  output: string;
  config?: string;
  tsConfigFilePath?: string;
  /** `Name: value` request headers for an http(s) input or configuration. */
  header?: string[];
  /** Milliseconds, as typed on the command line. */
  timeout?: string;
  /** `summary` or `full`. */
  schemaDocs?: string;
  /** Exit with a non-zero code when the run logged a warning. */
  strict?: boolean;
  verbose?: boolean;
  quiet?: boolean;
}

/**
 * Validates the input path or URL.
 *
 * Any file path is accepted here and checked when it is read. A URL must use
 * http or https; its host is not restricted, because the generator runs on
 * the developer's own machine against a document they chose, and fetching an
 * intranet service's `/v3/api-docs` is the common case.
 *
 * @param input - Input path or URL
 * @returns true if valid
 */
export function validateInput(input: string): boolean {
  if (!input) return false;
  let url: URL;
  try {
    url = new URL(input);
  } catch {
    // Not a URL: a file path, which parseOpenAPI reads.
    return true;
  }
  // A Windows path such as C:\spec.json parses as a URL with scheme "c:".
  if (/^[a-z]:$/i.test(url.protocol)) return true;
  return url.protocol === 'http:' || url.protocol === 'https:';
}

/**
 * Parses `Name: value` header arguments.
 *
 * @param headers - The raw `--header` values
 * @returns The headers by name
 * @throws GeneratorError of kind `input` for a value without a name
 */
export function parseHeaders(
  headers: readonly string[] = [],
): Record<string, string> {
  const parsed: Record<string, string> = {};
  for (const header of headers) {
    const separator = header.indexOf(':');
    const name = separator > 0 ? header.slice(0, separator).trim() : '';
    if (!name) {
      throw new GeneratorError(
        'input',
        `Invalid --header "${header}": expected "Name: value".`,
      );
    }
    parsed[name] = header.slice(separator + 1).trim();
  }
  return parsed;
}

function parseTimeout(timeout: string | undefined): number | undefined {
  if (timeout === undefined) return undefined;
  const value = Number(timeout);
  if (!Number.isInteger(value) || value <= 0) {
    throw new GeneratorError(
      'input',
      `Invalid --timeout "${timeout}": expected a positive number of milliseconds.`,
    );
  }
  return value;
}

function parseSchemaDocs(schemaDocs: string | undefined): SchemaDocs {
  if (schemaDocs === undefined || schemaDocs === 'summary') return 'summary';
  if (schemaDocs === 'full') return 'full';
  throw new GeneratorError(
    'input',
    `Invalid --schema-docs "${schemaDocs}": expected "summary" or "full".`,
  );
}

function logLevel(options: GenerateCommandOptions): LogLevel {
  if (options.verbose) return 'verbose';
  if (options.quiet) return 'quiet';
  return 'normal';
}

/**
 * Reports a failure as one actionable line; the stack and the causes only
 * with `--verbose`.
 *
 * @returns The exit code the failure maps to
 */
function reportFailure(
  logger: Logger,
  error: unknown,
  verbose: boolean,
): number {
  if (error instanceof GeneratorError) {
    logger.error(error.message);
    if (verbose && error.cause !== undefined) {
      logger.error('Caused by:', error.cause);
    }
    return error.exitCode;
  }
  const message = error instanceof Error ? error.message : String(error);
  logger.error(`Code generation failed: ${message}`);
  if (verbose) {
    logger.error('', error);
  } else {
    logger.error(
      'Rerun with --verbose for the stack trace, and report it at https://github.com/Ahoo-Wang/Wow/issues if the input is valid.',
    );
  }
  return EXIT_CODES.internal;
}

/**
 * Runs the `generate` command.
 *
 * @param options - The parsed command options
 * @param logger - Where to report; a console logger at the level the options ask for by default
 * @returns The exit code: see {@link EXIT_CODES}
 */
export async function runGenerate(
  options: GenerateCommandOptions,
  logger: Logger = new ConsoleLogger({ level: logLevel(options) }),
): Promise<number> {
  if (!validateInput(options.input)) {
    logger.error(
      `Invalid input "${options.input}": expected a file path or an http(s) URL.`,
    );
    return EXIT_CODES.input;
  }
  try {
    logger.debug(`wow-generator v${packageJson.version}`);
    const generatorOptions: GeneratorOptions = {
      inputPath: options.input,
      outputDir: options.output,
      configPath: options.config,
      tsConfigFilePath: options.tsConfigFilePath,
      headers: parseHeaders(options.header),
      timeoutMs: parseTimeout(options.timeout),
      schemaDocs: parseSchemaDocs(options.schemaDocs),
      logger,
    };
    const result = await new CodeGenerator(generatorOptions).generate();
    const warnings = result.warnings
      ? `, ${result.warnings} warning${result.warnings === 1 ? '' : 's'}`
      : '';
    const config = result.configPath
      ? ` with ${relative(process.cwd(), result.configPath) || result.configPath}`
      : '';
    logger.info(
      `Generated ${result.files.length} files into ${options.output}${config}${warnings}`,
    );
    if (options.strict && result.warnings > 0) {
      logger.error(
        `--strict: the run logged ${result.warnings} warning${result.warnings === 1 ? '' : 's'}.`,
      );
      return EXIT_CODES.specification;
    }
    return EXIT_CODES.success;
  } catch (error) {
    return reportFailure(logger, error, !!options.verbose);
  }
}

/**
 * Action handler for the generate command: runs it and sets the process exit
 * code.
 *
 * @param options - Command options
 */
export async function generateAction(options: GenerateCommandOptions) {
  process.once('SIGINT', () => {
    console.error('Generation interrupted by user');
    process.exit(EXIT_CODES.interrupted);
  });
  process.exitCode = await runGenerate(options);
}
