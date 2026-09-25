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

import { describe, expect, it, vi } from 'vitest';
import { downloadFile, fileName } from '../src/ui/download.js';

describe('fileName', () => {
  it("is the view and the day, in the user's own words", () => {
    expect(fileName('订单待办', '2026-09-20', 'csv')).toBe(
      '订单待办-2026-09-20.csv',
    );
  });

  it('replaces what a file system will not take', () => {
    // A newline in a title would otherwise reach whatever header the file is
    // uploaded under next.
    expect(fileName('2026/09: 待办\n表', '2026-09-20', 'csv')).toBe(
      '2026 09 待办 表-2026-09-20.csv',
    );
  });

  it('lets the day name the file when the title says nothing usable', () => {
    // Rather than a file called `-.csv`.
    expect(fileName('  ', '2026-09-20', 'csv')).toBe('2026-09-20.csv');
    expect(fileName('///', '2026-09-20', 'csv')).toBe('2026-09-20.csv');
  });

  /**
   * A title is free text, so it can be longer than a file name may be. The
   * day and the extension are what the name cannot lose — they are what
   * tells two exports of the same view apart — so the title is what gives
   * way, and it gives way at a known length rather than wherever the
   * browser or the file system happens to cut (B12).
   */
  it('caps the title, and keeps the day and the extension whole', () => {
    const name = fileName('待'.repeat(200), '2026-09-20', 'csv');

    expect(name).toBe(`${'待'.repeat(80)}-2026-09-20.csv`);
    expect(name.endsWith('-2026-09-20.csv')).toBe(true);
  });

  it('does not leave the gap the cut landed in', () => {
    // 80 characters that end in a space once the slash is replaced: the
    // name would otherwise read `… -2026-09-20.csv`.
    const title = `${'a'.repeat(79)}/word`;

    expect(fileName(title, '2026-09-20', 'csv')).toBe(
      `${'a'.repeat(79)}-2026-09-20.csv`,
    );
  });
});

describe('downloadFile', () => {
  it('hands the browser a blob, and takes the URL back again', () => {
    const createObjectURL = vi.fn(() => 'blob:one');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const clicks: HTMLAnchorElement[] = [];
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        clicks.push(this);
      });

    downloadFile({ name: 'orders.csv', content: 'a,b\r\n', type: 'text/csv' });

    expect(click).toHaveBeenCalledTimes(1);
    expect(clicks[0].download).toBe('orders.csv');
    expect(clicks[0].getAttribute('href')).toBe('blob:one');
    expect(clicks[0].rel).toBe('noopener');
    // The anchor never outlives the click, and neither does the URL: a blob
    // held for the session is the whole file kept in memory.
    expect(clicks[0].isConnected).toBe(false);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:one');
  });

  it('frees the blob even when the click is refused', () => {
    const revokeObjectURL = vi.fn();
    Object.assign(URL, {
      createObjectURL: vi.fn(() => 'blob:two'),
      revokeObjectURL,
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(() =>
      downloadFile({ name: 'orders.csv', content: '', type: 'text/csv' }),
    ).toThrow('blocked');
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:two');
    expect(document.querySelector('a[download]')).toBeNull();
  });
});
