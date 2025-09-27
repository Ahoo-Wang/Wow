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

import { Logger } from '../types';

/**
 * Default console-based logger implementation.
 * Provides friendly colored output for different log levels.
 */
export class ConsoleLogger implements Logger {
  private getTimestamp(): string {
    return new Date().toISOString().slice(11, 19); // HH:MM:SS format
  }

  info(message: string, ...params: any[]): void {
    const timestamp = this.getTimestamp();
    if (params.length > 0) {
      console.log(`[${timestamp}] ℹ️  ${message}`, ...params);
    } else {
      console.log(`[${timestamp}] ℹ️  ${message}`);
    }
  }

  success(message: string, ...params: any[]): void {
    const timestamp = this.getTimestamp();
    if (params.length > 0) {
      console.log(`[${timestamp}] ✅ ${message}`, ...params);
    } else {
      console.log(`[${timestamp}] ✅ ${message}`);
    }
  }

  error(message: string, ...params: any[]): void {
    const timestamp = this.getTimestamp();
    if (params.length > 0) {
      console.error(`[${timestamp}] ❌ ${message}`, ...params);
    } else {
      console.error(`[${timestamp}] ❌ ${message}`);
    }
  }

  progress(message: string, level = 0, ...params: any[]): void {
    const timestamp = this.getTimestamp();
    const indent = '  '.repeat(level);
    if (params.length > 0) {
      console.log(`[${timestamp}] 🔄 ${indent}${message}`, ...params);
    } else {
      console.log(`[${timestamp}] 🔄 ${indent}${message}`);
    }
  }

  progressWithCount(
    current: number,
    total: number,
    message: string,
    level = 0,
    ...params: any[]
  ): void {
    const timestamp = this.getTimestamp();
    const indent = '  '.repeat(level);
    const countStr = `[${current}/${total}]`;
    if (params.length > 0) {
      console.log(
        `[${timestamp}] 🔄 ${indent}${countStr} ${message}`,
        ...params,
      );
    } else {
      console.log(`[${timestamp}] 🔄 ${indent}${countStr} ${message}`);
    }
  }
}

/**
 * Silent logger that suppresses all output.
 */
export class SilentLogger implements Logger {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  info(_message: string, ...params: any[]): void {
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  success(_message: string, ...params: any[]): void {
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  error(_message: string, ...params: any[]): void {
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  progress(_message: string, ...params: any[]): void {
  }

  /* eslint-disable @typescript-eslint/no-unused-vars */
  progressWithCount(
    _current: number,
    _total: number,
    _message: string,
    _level = 0,
    ..._params: any[]
  ): void {
  }

  /* eslint-enable @typescript-eslint/no-unused-vars */
}
