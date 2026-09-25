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
import { ConsoleLogger, SilentLogger } from '../../src/api/logger';

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
    logger.debug('debug');
    logger.info('info');
    logger.warn('warn');
    logger.error('error');
  }

  it('prints the outcome, warnings and errors by default, without the details', () => {
    emitAll(new ConsoleLogger({ decorate: false }));

    expect(log.mock.calls).toEqual([['info']]);
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

    expect(log.mock.calls).toEqual([['[12:00:00] debug'], ['[12:00:00] info']]);
    expect(warnSpy.mock.calls).toEqual([['[12:00:00] warning: warn']]);
    expect(error.mock.calls).toEqual([['[12:00:00] error: error']]);
  });

  it('passes params through after the message', () => {
    const logger = new ConsoleLogger({ level: 'verbose', decorate: false });

    logger.debug('step', 'param', 2);

    expect(log.mock.calls).toEqual([['[12:00:00] step', 'param', 2]]);
  });

  it('decorates lines with symbols on a terminal', () => {
    const logger = new ConsoleLogger({ level: 'verbose', decorate: true });

    logger.debug('detail');
    logger.info('done');
    logger.warn('careful');
    logger.error('failed', 'detail');

    expect(log).toHaveBeenCalledWith('[12:00:00] ℹ️  detail');
    expect(log).toHaveBeenCalledWith('[12:00:00] ✅ done');
    expect(warnSpy).toHaveBeenCalledWith('[12:00:00] ⚠️  careful');
    expect(error).toHaveBeenCalledWith('[12:00:00] ❌ failed', 'detail');
  });

  it('stays plain when NO_COLOR is set, even on a terminal', () => {
    const isTTY = process.stdout.isTTY;
    process.stdout.isTTY = true;
    vi.stubEnv('NO_COLOR', '1');
    try {
      new ConsoleLogger().info('done');
      expect(log).toHaveBeenCalledWith('done');
    } finally {
      process.stdout.isTTY = isTTY;
    }
  });
});

describe('SilentLogger', () => {
  it('prints nothing', () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const logger = new SilentLogger();

    logger.debug();
    logger.info();
    logger.warn();
    logger.error();

    expect(log).not.toHaveBeenCalled();
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
  });
});
