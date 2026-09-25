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

import type { Logger } from '../api/logger';

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

  debug(message: string, ...params: unknown[]): void {
    this.delegate.debug(message, ...params);
  }

  info(message: string, ...params: unknown[]): void {
    this.delegate.info(message, ...params);
  }

  warn(message: string, ...params: unknown[]): void {
    this.count++;
    this.delegate.warn(message, ...params);
  }

  error(message: string, ...params: unknown[]): void {
    this.delegate.error(message, ...params);
  }
}
