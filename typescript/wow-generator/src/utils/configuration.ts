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

import { isAbsolute, resolve } from 'path';
import type { GeneratorConfiguration, Logger } from '../types';
import { warn } from './logger';
import { parseContent } from './parsers';
import { loadResource } from './resources';

/** Top-level keys a generator configuration may declare. */
const CONFIGURATION_KEYS = ['apiClients'];
/** Keys an `apiClients` entry may declare. */
const API_CLIENT_KEYS = ['ignorePathParameters'];

/**
 * Where a configuration is read from, and whether the caller chose that place.
 */
export interface ConfigurationSource {
  /** A file path, or an http(s) URL. */
  readonly path: string;
  /**
   * True when the caller asked for this path. An absent file is then a
   * mistake worth failing on; under the default path it is merely the common
   * case of a project that has no configuration.
   */
  readonly explicit: boolean;
}

/**
 * Reads, validates and reports a generator configuration.
 *
 * A configuration that fails to load used to degrade to the defaults with a
 * single line among hundreds of progress lines, so a misplaced file looked
 * exactly like a generator that ignores its options. Loading now either
 * succeeds and says what it read, or fails loudly - with the sole exception of
 * the default path being absent, which stays a normal, quiet outcome.
 *
 * @param source - Where to read the configuration from
 * @param logger - Receives the resolved settings, and any warning about them
 * @returns The validated configuration, empty when there is none to read
 * @throws Error when the configuration cannot be read, parsed or understood
 */
export async function loadConfiguration(
  source: ConfigurationSource,
  logger: Logger,
): Promise<GeneratorConfiguration> {
  const origin = describeSource(source.path);
  logger.info(`Reading configuration: ${origin}`);
  let content: string;
  try {
    content = await loadResource(source.path);
  } catch (error) {
    if (!source.explicit && isFileNotFound(error)) {
      logger.info(
        `No configuration file at ${origin}, generating with defaults`,
      );
      return {};
    }
    throw new Error(`Cannot read configuration ${origin}: ${reason(error)}`, {
      cause: error,
    });
  }
  if (!content.trim()) {
    warn(logger, `Configuration ${origin} is empty, generating with defaults`);
    return {};
  }
  let parsed: unknown;
  try {
    parsed = parseContent(content, source.path);
  } catch (error) {
    throw new Error(`Cannot parse configuration ${origin}: ${reason(error)}`, {
      cause: error,
    });
  }
  const config = validateConfiguration(parsed, origin, logger);
  logger.info(`Configuration loaded from ${origin}: ${describe(config)}`);
  return config;
}

/**
 * Resolves a configuration path to what the user needs to see in a message.
 * A relative path is resolved against the working directory, because "not
 * found" is unactionable until it says which file was looked for.
 *
 * @param path - The configured file path or URL
 * @returns An absolute path, or the URL unchanged
 */
function describeSource(path: string): string {
  if (/^https?:\/\//.test(path) || isAbsolute(path)) {
    return path;
  }
  return resolve(path);
}

/** Tells whether an error reports a missing file rather than a failed read. */
function isFileNotFound(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as NodeJS.ErrnoException).code === 'ENOENT'
  );
}

/** Renders an error as the tail of a sentence. */
function reason(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Names a value's type the way a configuration author would recognise it. */
function typeOf(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'an array';
  return `a ${typeof value}`;
}

/** Tells whether a value is a plain object a configuration block can be. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Warns about keys the generator does not read.
 *
 * A misspelled key is the failure this whole module exists for: it parses,
 * it validates, and it does nothing at all.
 *
 * @param logger - Receives the warning
 * @param origin - Where the configuration was read from
 * @param scope - The block the keys sit in, for the message
 * @param value - The block to inspect
 * @param known - The keys the generator reads
 */
function warnUnknownKeys(
  logger: Logger,
  origin: string,
  scope: string,
  value: Record<string, unknown>,
  known: string[],
): void {
  const unknown = Object.keys(value).filter(key => !known.includes(key));
  if (!unknown.length) {
    return;
  }
  warn(
    logger,
    `Ignoring unknown ${scope} option(s) in ${origin}: ${unknown.join(', ')}. Known option(s): ${known.join(', ')}`,
  );
}

/**
 * Checks a parsed configuration against the options the generator reads.
 *
 * Shape errors throw: a block the generator cannot read is a request the user
 * made and the generator would otherwise drop. Unknown keys only warn, so a
 * configuration written for a newer version still generates.
 *
 * @param parsed - The parsed configuration document
 * @param origin - Where it was read from, for messages
 * @param logger - Receives warnings about keys the generator ignores
 * @returns The same configuration, typed
 * @throws Error when a block or option has a shape the generator cannot read
 */
export function validateConfiguration(
  parsed: unknown,
  origin: string,
  logger: Logger,
): GeneratorConfiguration {
  if (!isRecord(parsed)) {
    throw new Error(
      `Configuration ${origin} must be a JSON or YAML object, found ${typeOf(parsed)}`,
    );
  }
  warnUnknownKeys(logger, origin, 'configuration', parsed, CONFIGURATION_KEYS);
  validateApiClients(parsed.apiClients, origin, logger);
  return parsed as GeneratorConfiguration;
}

/** Checks the `apiClients` block and each tag entry within it. */
function validateApiClients(
  apiClients: unknown,
  origin: string,
  logger: Logger,
): void {
  if (apiClients === undefined) {
    return;
  }
  if (!isRecord(apiClients)) {
    throw new Error(
      `apiClients in ${origin} must be an object keyed by tag name, found ${typeOf(apiClients)}`,
    );
  }
  for (const [tag, apiClient] of Object.entries(apiClients)) {
    if (!isRecord(apiClient)) {
      throw new Error(
        `apiClients["${tag}"] in ${origin} must be an object, found ${typeOf(apiClient)}`,
      );
    }
    warnUnknownKeys(
      logger,
      origin,
      `apiClients["${tag}"]`,
      apiClient,
      API_CLIENT_KEYS,
    );
    const { ignorePathParameters } = apiClient;
    if (
      ignorePathParameters !== undefined &&
      !(
        Array.isArray(ignorePathParameters) &&
        ignorePathParameters.every(name => typeof name === 'string')
      )
    ) {
      throw new Error(
        `apiClients["${tag}"].ignorePathParameters in ${origin} must be an array of strings, found ${typeOf(ignorePathParameters)}`,
      );
    }
  }
}

/**
 * Summarises the settings a configuration resolved to, so the log answers
 * "did my option take effect?" without a second run.
 *
 * @param config - The validated configuration
 * @returns A one-line summary of every option the generator reads
 */
function describe(config: GeneratorConfiguration): string {
  const tags = Object.keys(config.apiClients ?? {});
  return `apiClients=${tags.length ? tags.join(', ') : 'none'}`;
}
