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

import type { Logger } from '../types';

/**
 * How much a {@link ConsoleLogger} prints.
 *
 * - `quiet`: warnings and errors only.
 * - `normal`: also the outcome of each step (`success`).
 * - `verbose`: also every detail (`info`, `progress`), with timestamps.
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
 * Details go to `info` and `progress`, which only `verbose` prints; the
 * default prints outcomes, warnings and errors, so a warning is not lost among
 * hundreds of progress lines.
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

  info(message: string, ...params: any[]): void {
    if (this.level !== 'verbose') return;
    console.log(this.format('ℹ️ ', '', message), ...params);
  }

  warn(message: string, ...params: unknown[]): void {
    console.warn(this.format('⚠️ ', 'warning', message), ...params);
  }

  success(message: string, ...params: any[]): void {
    if (this.level === 'quiet') return;
    console.log(this.format('✅', '', message), ...params);
  }

  error(message: string, ...params: any[]): void {
    console.error(this.format('❌', 'error', message), ...params);
  }

  progress(message: string, level = 0, ...params: any[]): void {
    if (this.level !== 'verbose') return;
    const indent = '  '.repeat(level);
    console.log(this.format('🔄', '', `${indent}${message}`), ...params);
  }

  progressWithCount(
    current: number,
    total: number,
    message: string,
    level = 0,
    ...params: any[]
  ): void {
    if (this.level !== 'verbose') return;
    const indent = '  '.repeat(level);
    console.log(
      this.format('🔄', '', `${indent}[${current}/${total}] ${message}`),
      ...params,
    );
  }
}

/**
 * Silent logger that suppresses all output.
 */
export class SilentLogger implements Logger {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  info(_message: string, ...params: any[]): void {}

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  warn(_message: string, ...params: unknown[]): void {}

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  success(_message: string, ...params: any[]): void {}

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  error(_message: string, ...params: any[]): void {}

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  progress(_message: string, ...params: any[]): void {}

  /* eslint-disable @typescript-eslint/no-unused-vars */
  progressWithCount(
    _current: number,
    _total: number,
    _message: string,
    _level = 0,
    ..._params: any[]
  ): void {}

  /* eslint-enable @typescript-eslint/no-unused-vars */
}

/**
 * Logs a warning through a logger that may not implement one.
 *
 * {@link Logger.warn} is optional, so that adding it did not break loggers
 * written against the previous interface; those fall back to {@link
 * Logger.info} rather than losing the message.
 *
 * @param logger - The logger to write to
 * @param message - The warning
 * @param params - Additional values to log
 */
export function warn(
  logger: Logger,
  message: string,
  ...params: unknown[]
): void {
  if (logger.warn) {
    logger.warn(message, ...params);
    return;
  }
  logger.info(message, ...params);
}

/**
 * Forwards to another logger and counts the warnings passed through, so a
 * run can report them and `--strict` can fail on them.
 */
export class WarningCounter implements Logger {
  private count = 0;

  constructor(private readonly delegate: Logger) {}

  /** How many warnings have been logged. */
  get warnings(): number {
    return this.count;
  }

  info(message: string, ...params: any[]): void {
    this.delegate.info(message, ...params);
  }

  warn(message: string, ...params: unknown[]): void {
    this.count++;
    warn(this.delegate, message, ...params);
  }

  success(message: string, ...params: any[]): void {
    this.delegate.success(message, ...params);
  }

  error(message: string, ...params: any[]): void {
    this.delegate.error(message, ...params);
  }

  progress(message: string, level?: number, ...params: any[]): void {
    this.delegate.progress(message, level, ...params);
  }

  progressWithCount(
    current: number,
    total: number,
    message: string,
    level?: number,
    ...params: any[]
  ): void {
    this.delegate.progressWithCount(current, total, message, level, ...params);
  }
}
