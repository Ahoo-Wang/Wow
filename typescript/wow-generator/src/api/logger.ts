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
 * Where a generation reports what it does.
 *
 * Each method takes a message and, optionally, values to print after it, as
 * `console.log` does. A run calls:
 *
 * - `debug` for every step and detail;
 * - `info` for the outcome of the run;
 * - `warn` for something it carried on past, but that the user probably did
 *   not intend; `GenerationResult.warnings` counts these calls;
 * - `error` for a failure.
 */
export interface Logger {
  debug(message: string, ...params: unknown[]): void;
  info(message: string, ...params: unknown[]): void;
  warn(message: string, ...params: unknown[]): void;
  error(message: string, ...params: unknown[]): void;
}

/**
 * How much a {@link ConsoleLogger} prints.
 *
 * - `quiet`: warnings and errors only.
 * - `normal`: also `info`, the outcome of the run.
 * - `verbose`: also `debug`, every detail, with timestamps.
 */
export type LogLevel = 'quiet' | 'normal' | 'verbose';

export interface ConsoleLoggerOptions {
  /** Defaults to `normal`. */
  readonly level?: LogLevel;
  /**
   * Prefix lines with symbols. Defaults to true on a TTY unless `NO_COLOR`
   * is set, so CI logs and pipes stay plain.
   */
  readonly decorate?: boolean;
}

function defaultDecorate(): boolean {
  return !!process.stdout?.isTTY && !process.env.NO_COLOR;
}

/**
 * Console logger for the CLI.
 *
 * Details go to `debug`, which only `verbose` prints; the default prints the
 * outcome, warnings and errors, so a warning is not lost among hundreds of
 * detail lines.
 */
export class ConsoleLogger implements Logger {
  private readonly level: LogLevel;
  private readonly decorate: boolean;

  constructor(options: ConsoleLoggerOptions = {}) {
    this.level = options.level ?? 'normal';
    this.decorate = options.decorate ?? defaultDecorate();
  }

  private format(symbol: string, label: string, message: string): string {
    const prefix = this.decorate ? `${symbol} ` : label ? `${label}: ` : '';
    const timestamp =
      this.level === 'verbose'
        ? `[${new Date().toTimeString().slice(0, 8)}] `
        : '';
    return `${timestamp}${prefix}${message}`;
  }

  debug(message: string, ...params: unknown[]): void {
    if (this.level !== 'verbose') return;
    console.log(this.format('ℹ️ ', '', message), ...params);
  }

  info(message: string, ...params: unknown[]): void {
    if (this.level === 'quiet') return;
    console.log(this.format('✅', '', message), ...params);
  }

  warn(message: string, ...params: unknown[]): void {
    console.warn(this.format('⚠️ ', 'warning', message), ...params);
  }

  error(message: string, ...params: unknown[]): void {
    console.error(this.format('❌', 'error', message), ...params);
  }
}

/**
 * Logger that prints nothing.
 */
export class SilentLogger implements Logger {
  debug(): void {}

  info(): void {}

  warn(): void {}

  error(): void {}
}
