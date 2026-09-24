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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ConsoleLogger,
  SilentLogger,
  WarningCounter,
  warn,
} from '../../src/utils/logger';

describe('ConsoleLogger', () => {
  let log: ReturnType<typeof vi.spyOn>;
  let warnSpy: ReturnType<typeof vi.spyOn>;
  let error: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 0, 1, 12, 0, 0));
    log = vi.spyOn(console, 'log').mockImplementation(() => {});
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    error = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function emitAll(logger: ConsoleLogger) {
    logger.info('info');
    logger.progress('progress');
    logger.progressWithCount(1, 2, 'counted');
    logger.success('success');
    logger.warn('warn');
    logger.error('error');
  }

  it('prints outcomes, warnings and errors by default, without the details', () => {
    emitAll(new ConsoleLogger({ decorate: false }));

    expect(log.mock.calls).toEqual([['success']]);
    expect(warnSpy.mock.calls).toEqual([['warning: warn']]);
    expect(error.mock.calls).toEqual([['error: error']]);
  });

  it('prints only warnings and errors when quiet', () => {
    emitAll(new ConsoleLogger({ level: 'quiet', decorate: false }));

    expect(log).not.toHaveBeenCalled();
    expect(warnSpy.mock.calls).toEqual([['warning: warn']]);
    expect(error.mock.calls).toEqual([['error: error']]);
  });

  it('prints every detail with a timestamp when verbose', () => {
    emitAll(new ConsoleLogger({ level: 'verbose', decorate: false }));

    expect(log.mock.calls).toEqual([
      ['[12:00:00] info'],
      ['[12:00:00] progress'],
      ['[12:00:00] [1/2] counted'],
      ['[12:00:00] success'],
    ]);
    expect(warnSpy.mock.calls).toEqual([['[12:00:00] warning: warn']]);
  });

  it('indents progress by level and passes params through', () => {
    const logger = new ConsoleLogger({ level: 'verbose', decorate: false });

    logger.progress('step', 2, 'param');
    logger.progressWithCount(2, 5, 'counted', 1, 'param');

    expect(log.mock.calls).toEqual([
      ['[12:00:00]     step', 'param'],
      ['[12:00:00]   [2/5] counted', 'param'],
    ]);
  });

  it('decorates lines with symbols on a terminal', () => {
    const logger = new ConsoleLogger({ decorate: true });

    logger.success('done');
    logger.warn('careful');
    logger.error('failed', 'detail');

    expect(log).toHaveBeenCalledWith('✅ done');
    expect(warnSpy).toHaveBeenCalledWith('⚠️  careful');
    expect(error).toHaveBeenCalledWith('❌ failed', 'detail');
  });

  it('stays plain when NO_COLOR is set, even on a terminal', () => {
    const isTTY = process.stdout.isTTY;
    process.stdout.isTTY = true;
    vi.stubEnv('NO_COLOR', '1');
    try {
      new ConsoleLogger().success('done');
      expect(log).toHaveBeenCalledWith('done');
    } finally {
      process.stdout.isTTY = isTTY;
    }
  });
});

describe('WarningCounter', () => {
  it('forwards every call and counts the warnings', () => {
    const delegate = {
      info: vi.fn(),
      warn: vi.fn(),
      success: vi.fn(),
      error: vi.fn(),
      progress: vi.fn(),
      progressWithCount: vi.fn(),
    };
    const counter = new WarningCounter(delegate);

    counter.info('i', 1);
    counter.warn('w1');
    counter.warn('w2', 2);
    counter.success('s');
    counter.error('e');
    counter.progress('p', 1);
    counter.progressWithCount(1, 2, 'c', 0);

    expect(counter.warnings).toBe(2);
    expect(delegate.info).toHaveBeenCalledWith('i', 1);
    expect(delegate.warn).toHaveBeenCalledWith('w2', 2);
    expect(delegate.success).toHaveBeenCalledWith('s');
    expect(delegate.error).toHaveBeenCalledWith('e');
    expect(delegate.progress).toHaveBeenCalledWith('p', 1);
    expect(delegate.progressWithCount).toHaveBeenCalledWith(1, 2, 'c', 0);
  });
});

describe('SilentLogger', () => {
  it('should not log any messages', () => {
    const consoleLogSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const consoleErrorSpy = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    const logger = new SilentLogger();

    logger.info('Test info');
    logger.warn('Test warn');
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

describe('warn', () => {
  it('uses the logger warning channel when there is one', () => {
    const logger = { info: vi.fn(), warn: vi.fn() } as any;

    warn(logger, 'Heads up', 'detail');

    expect(logger.warn).toHaveBeenCalledWith('Heads up', 'detail');
    expect(logger.info).not.toHaveBeenCalled();
  });

  it('falls back to info rather than dropping the message', () => {
    const logger = { info: vi.fn() } as any;

    warn(logger, 'Heads up', 'detail');

    expect(logger.info).toHaveBeenCalledWith('Heads up', 'detail');
  });
});
