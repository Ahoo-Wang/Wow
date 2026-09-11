/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { expect, it, vi } from 'vitest';
import { validateTimeZone } from '../src/lib/timeZone.js';
it('validates a repeated IANA zone once but never caches invalid zones', () => {
  const create = vi.spyOn(Intl, 'DateTimeFormat');
  try {
    for (let n = 0; n < 100; n++) validateTimeZone('Etc/GMT+7');
    expect(create).toHaveBeenCalledTimes(1);
    expect(() => validateTimeZone('not/a-zone')).toThrow();
    expect(() => validateTimeZone('not/a-zone')).toThrow();
    expect(create).toHaveBeenCalledTimes(3);
    validateTimeZone(undefined);
    validateTimeZone('+05:30');
    expect(create).toHaveBeenCalledTimes(3);
  } finally {
    create.mockRestore();
  }
});
