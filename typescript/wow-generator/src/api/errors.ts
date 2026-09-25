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
 * What a generation failure is about, which decides the CLI's exit code.
 *
 * - `input`: the OpenAPI document cannot be read, fetched or parsed, or it is
 *   not an OpenAPI 3 document.
 * - `configuration`: the generator configuration cannot be read, parsed or
 *   understood.
 * - `specification`: the document was read, but it describes something the
 *   generator cannot turn into code that compiles: a dangling, cyclic or
 *   external reference, names that collide after normalisation, malformed Wow
 *   metadata.
 * - `output`: the output directory cannot be written as asked: its manifest
 *   (`.wow-generator.json`) is corrupt, a path resolves outside it, or writing
 *   or deleting a file fails.
 */
export type GeneratorErrorKind =
  'input' | 'configuration' | 'specification' | 'output';

/**
 * Exit codes of the `wow-generator` CLI. Anything the generator did not
 * anticipate exits with {@link EXIT_CODES.internal}.
 */
export const EXIT_CODES = {
  success: 0,
  internal: 1,
  input: 2,
  configuration: 3,
  specification: 4,
  output: 5,
  interrupted: 130,
} as const;

/**
 * A failure the user can act on. Its message is written as one line that
 * names what went wrong and where; the CLI prints it without a stack trace.
 */
export class GeneratorError extends Error {
  constructor(
    readonly kind: GeneratorErrorKind,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = 'GeneratorError';
  }

  /** The CLI exit code this failure maps to. */
  get exitCode(): number {
    return EXIT_CODES[this.kind];
  }
}

/** Renders an error as the tail of a sentence. */
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
