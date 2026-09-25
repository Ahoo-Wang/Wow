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
import type { GeneratorConfiguration } from '../api/configuration';
import { DEFAULT_CONFIG_PATH } from '../api/configuration';
import { errorMessage, GeneratorError } from '../api/errors';
import type { Logger } from '../api/logger';
import { isIdentifier } from './naming';
import { parseContent } from './parsers';
import type { LoadResourceOptions } from './resources';
import { isHttpLocation, loadResource } from './resources';

// compat(fetcher): the configuration was named fetcher-generator.config.json before the
// package moved to Wow; read it, with a deprecation warning, when the new name is absent.
// Removed in v10.
export const LEGACY_CONFIG_PATH = './fetcher-generator.config.json';

/**
 * A configuration and the place it was read from.
 */
export interface ResolvedConfiguration {
  readonly config: GeneratorConfiguration;
  /** The absolute path or URL read, or undefined when there was none. */
  readonly origin?: string;
}

/**
 * Finds and loads the generator configuration.
 *
 * A path the caller names has to exist. Without one the generator reads
 * {@link DEFAULT_CONFIG_PATH}, then the pre-Wow name
 * {@link LEGACY_CONFIG_PATH} with a deprecation warning, and generates with
 * the defaults when neither exists.
 *
 * @param configPath - The path or URL the caller named, if any
 * @param logger - Receives the resolved settings and any warning
 * @param options - Headers and timeout for a configuration read over http(s)
 * @returns The configuration and where it came from
 * @throws GeneratorError of kind `configuration` when it cannot be read,
 * parsed or understood
 */
export async function resolveConfiguration(
  configPath: string | undefined,
  logger: Logger,
  options?: LoadResourceOptions,
): Promise<ResolvedConfiguration> {
  if (configPath !== undefined) {
    const config = await loadConfiguration(
      { path: configPath, explicit: true },
      logger,
      options,
    );
    return { config, origin: describeSource(configPath) };
  }
  for (const path of [DEFAULT_CONFIG_PATH, LEGACY_CONFIG_PATH]) {
    const config = await loadConfiguration(
      { path, explicit: false },
      logger,
      options,
    );
    if (config === undefined) continue;
    if (path === LEGACY_CONFIG_PATH) {
      logger.warn(
        `${describeSource(path)} uses the deprecated name; rename it to ${DEFAULT_CONFIG_PATH.slice(2)}. The old name is no longer read from v10.`,
      );
    }
    return { config, origin: describeSource(path) };
  }
  logger.debug('No configuration file found, generating with defaults');
  return { config: {} };
}

/** Top-level keys a generator configuration may declare. */
const CONFIGURATION_KEYS = ['apiClients'];
/** Keys an `apiClients` entry may declare. */
const API_CLIENT_KEYS = ['ignorePathParameters', 'methodNames'];

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
 * @param options - Headers and timeout for a configuration read over http(s)
 * @returns The validated configuration; undefined when a path the caller did
 * not name does not exist
 * @throws GeneratorError of kind `configuration` when the configuration
 * cannot be read, parsed or understood
 */
export async function loadConfiguration(
  source: ConfigurationSource & { explicit: true },
  logger: Logger,
  options?: LoadResourceOptions,
): Promise<GeneratorConfiguration>;
export async function loadConfiguration(
  source: ConfigurationSource,
  logger: Logger,
  options?: LoadResourceOptions,
): Promise<GeneratorConfiguration | undefined>;
export async function loadConfiguration(
  source: ConfigurationSource,
  logger: Logger,
  options?: LoadResourceOptions,
): Promise<GeneratorConfiguration | undefined> {
  const origin = describeSource(source.path);
  logger.debug(`Reading configuration: ${origin}`);
  let content: string;
  try {
    content = await loadResource(source.path, options);
  } catch (error) {
    if (!source.explicit && isFileNotFound(error)) {
      logger.debug(`No configuration file at ${origin}`);
      return undefined;
    }
    throw new GeneratorError(
      'configuration',
      `Cannot read configuration ${origin}: ${errorMessage(error)}`,
      { cause: error },
    );
  }
  if (!content.trim()) {
    logger.warn(`Configuration ${origin} is empty, generating with defaults`);
    return {};
  }
  let parsed: unknown;
  try {
    parsed = parseContent(content);
  } catch (error) {
    throw new GeneratorError(
      'configuration',
      `Cannot parse configuration ${origin}: ${errorMessage(error)}`,
      { cause: error },
    );
  }
  const config = validateConfiguration(parsed, origin, logger);
  logger.debug(`Configuration loaded from ${origin}: ${describe(config)}`);
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
  if (isHttpLocation(path) || isAbsolute(path)) {
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
  logger.warn(
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
    throw new GeneratorError(
      'configuration',
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
    throw new GeneratorError(
      'configuration',
      `apiClients in ${origin} must be an object keyed by tag name, found ${typeOf(apiClients)}`,
    );
  }
  for (const [tag, apiClient] of Object.entries(apiClients)) {
    if (!isRecord(apiClient)) {
      throw new GeneratorError(
        'configuration',
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
      throw new GeneratorError(
        'configuration',
        `apiClients["${tag}"].ignorePathParameters in ${origin} must be an array of strings, found ${typeOf(ignorePathParameters)}`,
      );
    }
    const { methodNames } = apiClient;
    if (methodNames === undefined) continue;
    if (!isRecord(methodNames)) {
      throw new GeneratorError(
        'configuration',
        `apiClients["${tag}"].methodNames in ${origin} must be an object mapping operationIds to method names, found ${typeOf(methodNames)}`,
      );
    }
    for (const [operationId, methodName] of Object.entries(methodNames)) {
      if (typeof methodName !== 'string' || !isIdentifier(methodName)) {
        throw new GeneratorError(
          'configuration',
          `apiClients["${tag}"].methodNames["${operationId}"] in ${origin} must be a valid method name, found ${JSON.stringify(methodName)}`,
        );
      }
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
