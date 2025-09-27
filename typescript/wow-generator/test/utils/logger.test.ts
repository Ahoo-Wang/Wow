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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ConsoleLogger, SilentLogger } from '../../src/utils/logger';

describe('ConsoleLogger', () => {
  beforeEach(() => {
    vi.spyOn(ConsoleLogger.prototype as any, 'getTimestamp').mockReturnValue(
      '12:00:00',
    );
  });
  it('should log info messages with emoji', () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {
    });
    const logger = new ConsoleLogger();

    logger.info('Test info message');

    expect(consoleSpy).toHaveBeenCalledWith('[12:00:00] ℹ️  Test info message');
    consoleSpy.mockRestore();
  });

  it('should log success messages with emoji', () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {
    });
    const logger = new ConsoleLogger();

    logger.success('Test success message');

    expect(consoleSpy).toHaveBeenCalledWith(
      '[12:00:00] ✅ Test success message',
    );
    consoleSpy.mockRestore();
  });

  it('should log error messages with emoji', () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {
    });
    const logger = new ConsoleLogger();

    logger.error('Test error message');

    expect(consoleSpy).toHaveBeenCalledWith('[12:00:00] ❌ Test error message');
    consoleSpy.mockRestore();
  });

  it('should log progress messages with emoji', () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {
    });
    const logger = new ConsoleLogger();

    logger.progress('Test progress message');

    expect(consoleSpy).toHaveBeenCalledWith(
      '[12:00:00] 🔄 Test progress message',
    );
    consoleSpy.mockRestore();
  });
});

describe('SilentLogger', () => {
  it('should not log any messages', () => {
    const consoleLogSpy = vi.spyOn(console, 'log').mockImplementation(() => {
    });
    const consoleErrorSpy = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {
      });
    const logger = new SilentLogger();

    logger.info('Test info');
    logger.success('Test success');
    logger.error('Test error');
    logger.progress('Test progress');
    logger.progressWithCount(1, 1, 'Test progress with count');

    expect(consoleLogSpy).not.toHaveBeenCalled();
    expect(consoleErrorSpy).not.toHaveBeenCalled();
    consoleLogSpy.mockRestore();
    consoleErrorSpy.mockRestore();
  });
});
