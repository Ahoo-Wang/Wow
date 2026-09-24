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
import {
  CSV_BOM,
  serializeCsv,
  writeCsv,
  type CsvOptions,
  type ExportColumn,
} from '../src/index.js';

const COLUMNS: ExportColumn[] = [
  { field: 'id', label: 'Order' },
  { field: 'amount', label: 'Amount' },
];

/** The reading a caller injects; here, the plainest one there is. */
const plain = (value: unknown): string =>
  value === null || value === undefined ? '' : String(value);

/** The file without its BOM and with its lines split, for readable assertions. */
function lines(csv: string): string[] {
  expect(csv.startsWith(CSV_BOM)).toBe(true);
  return csv.slice(CSV_BOM.length).split('\r\n');
}

describe('serializeCsv', () => {
  it('writes the column labels, then one line per row', () => {
    const csv = serializeCsv(
      [
        { id: 'o-1', amount: 10 },
        { id: 'o-2', amount: 20 },
      ],
      COLUMNS,
      plain,
    );

    // RFC 4180 ends every record with CRLF, the last one included, which is
    // the empty string after the final separator.
    expect(lines(csv)).toEqual(['Order,Amount', 'o-1,10', 'o-2,20', '']);
  });

  it('writes the header alone when there are no rows', () => {
    expect(lines(serializeCsv([], COLUMNS, plain))).toEqual([
      'Order,Amount',
      '',
    ]);
  });

  it('quotes a field holding a comma, a quote or a newline', () => {
    const csv = serializeCsv(
      [
        { id: 'a,b', amount: 'say "hi"' },
        { id: 'two\nlines', amount: 'carriage\rreturn' },
      ],
      COLUMNS,
      plain,
    );

    expect(lines(csv)).toEqual([
      'Order,Amount',
      '"a,b","say ""hi"""',
      '"two\nlines","carriage\rreturn"',
      '',
    ]);
  });

  it('quotes a label that needs it, exactly as a value', () => {
    const csv = serializeCsv([], [{ field: 'id', label: 'Order, no.' }], plain);

    expect(lines(csv)).toEqual(['"Order, no."', '']);
  });

  it('writes an empty field for a value the row does not hold', () => {
    const csv = serializeCsv([{ id: null }], COLUMNS, plain);

    // `null` and a path that is not there are both nothing to show; the
    // reading decides that, and this one says so as an empty field.
    expect(lines(csv)).toEqual(['Order,Amount', ',', '']);
  });

  it('reads a value out of a nested path, the way a cell does', () => {
    const csv = serializeCsv(
      [{ state: { status: 'PENDING' } }],
      [{ field: 'state.status', label: 'Status' }],
      plain,
    );

    expect(lines(csv)).toEqual(['Status', 'PENDING', '']);
  });

  it('hands the column to the formatter beside the value', () => {
    const format = vi.fn(
      (value: unknown, column: ExportColumn) =>
        `${column.field}=${plain(value)}`,
    );

    const csv = serializeCsv([{ id: 'o-1', amount: 10 }], COLUMNS, format);

    expect(lines(csv)[1]).toBe('id=o-1,amount=10');
    expect(format).toHaveBeenCalledWith('o-1', COLUMNS[0]);
    expect(format).toHaveBeenCalledWith(10, COLUMNS[1]);
  });

  it('coerces what a formatter answers with, whatever it answers', () => {
    // The type says `string`; a host's formatter is still a function somebody
    // else wrote, and `undefined` written as the word would be a value the
    // table never showed.
    const csv = serializeCsv([{ id: 'o-1', amount: 10 }], COLUMNS, value =>
      typeof value === 'number' ? (value as unknown as string) : undefined!,
    );

    expect(lines(csv)[1]).toBe(',10');
  });
});

/**
 * A file leaves the page and is opened in a spreadsheet, often by someone
 * other than whoever exported it, so a cell it could evaluate is written with
 * an apostrophe in front (OWASP, CSV Injection) unless the caller turns that
 * off.
 */
describe('formulas in an exported file', () => {
  const TEXT: ExportColumn[] = [{ field: 'note', label: 'Note' }];

  /** The one cell a value of `note` is written as. */
  const cell = (note: unknown, options?: CsvOptions) =>
    lines(serializeCsv([{ note }], TEXT, plain, options))[1];

  it.each([
    ['=', '=1+2', "'=1+2"],
    ['+', '+1+2', "'+1+2"],
    ['-', '-1+2', "'-1+2"],
    ['@', '@SUM(A1)', "'@SUM(A1)"],
    // A tab needs no quoting; the carriage return does, apostrophe inside.
    ['a tab', '\t=1+2', "'\t=1+2"],
    ['a carriage return', '\r=1+2', '"\'\r=1+2"'],
  ])('neutralizes a text starting with %s', (_start, note, written) => {
    expect(cell(note)).toBe(written);
  });

  it('quotes a neutralized field that needs quoting, apostrophe inside', () => {
    expect(cell('=HYPERLINK("http://x","y")')).toBe(
      '"\'=HYPERLINK(""http://x"",""y"")"',
    );
  });

  it('leaves a text alone that only holds such a character further in', () => {
    expect(cell('a=b')).toBe('a=b');
    expect(cell('x-1')).toBe('x-1');
  });

  it('never touches a number, however its reading starts', () => {
    // A negative amount formatted with separators and a currency is still a
    // number: the value it was read from decides, not its text.
    const csv = serializeCsv([{ note: -1204.5 }, { note: -12 }], TEXT, value =>
      value === -12 ? '-$12.00' : '-1,204.50',
    );
    expect(lines(csv)).toEqual(['Note', '"-1,204.50"', '-$12.00', '']);
    expect(cell(-5)).toBe('-5');
    expect(cell(BigInt(-5))).toBe('-5');
  });

  it('leaves a text alone that is a plain number, a text field’s "-12.5"', () => {
    // A spreadsheet reads these as the number they are and never as a
    // formula; an apostrophe would only make the file differ from the screen.
    expect(cell('-12.5')).toBe('-12.5');
    expect(cell('+3')).toBe('+3');
    expect(cell('-.5')).toBe('-.5');
    expect(cell('-1e3')).toBe('-1e3');
    // Anything more than one plain number is not: an operator, a separator,
    // a space.
    expect(cell('-1-1')).toBe("'-1-1");
    expect(cell('-1,204')).toBe('"\'-1,204"');
    expect(cell('- 1')).toBe("'- 1");
  });

  it('neutralizes a header label as well, since a definition names it', () => {
    const csv = serializeCsv([], [{ field: 'note', label: '=Note' }], plain);
    expect(lines(csv)).toEqual(["'=Note", '']);
  });

  it('writes every cell as it reads when the caller turns it off', () => {
    const off = { neutralizeFormulas: false };
    expect(cell('=1+2', off)).toBe('=1+2');
    expect(cell('\t@x', off)).toBe('\t@x');
    expect(
      lines(serializeCsv([], [{ field: 'n', label: '-Note' }], plain, off)),
    ).toEqual(['-Note', '']);
    // Left out, or anything but `false`, is on.
    expect(cell('=1+2', {})).toBe("'=1+2");
    expect(cell('=1+2', { neutralizeFormulas: true })).toBe("'=1+2");
  });

  it('is the same rule for cells already read (writeCsv)', () => {
    const csv = writeCsv(
      ['@Group', 'Sum'],
      [
        [
          { value: '=cmd', text: '=cmd' },
          { value: -3, text: '-3.00' },
        ],
      ],
    );
    expect(lines(csv)).toEqual(["'@Group,Sum", "'=cmd,-3.00", '']);
  });
});
