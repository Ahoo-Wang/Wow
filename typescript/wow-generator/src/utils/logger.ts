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
  info(message: string, ...params: any[]): void {
    console.log(`ℹ️  ${message}`, params);
  }

  success(message: string, ...params: any[]): void {
    console.log(`✅ ${message}`, params);
  }

  error(message: string, ...params: any[]): void {
    console.error(`❌ ${message}`, params);
  }

  progress(message: string, ...params: any[]): void {
    console.log(`🔄 ${message}`, params);
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
}
