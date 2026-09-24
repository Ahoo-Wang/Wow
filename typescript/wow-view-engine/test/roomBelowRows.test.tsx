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
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { useRef } from 'react';
import { useRoomBelowRows } from '../src/ui/record/roomBelowRows.js';

/**
 * jsdom lays nothing out, so the two heights the hook compares are given:
 * the port's inner height and the table's.
 */
function Port({
  port,
  table,
  enabled = true,
}: {
  port: number;
  table: number;
  enabled?: boolean;
}) {
  const portRef = useRef<HTMLDivElement>(null);
  const tableRef = useRef<HTMLTableElement>(null);
  const room = useRoomBelowRows(portRef, tableRef, enabled);
  return (
    <div
      ref={node => {
        portRef.current = node;
        if (node) Object.defineProperty(node, 'clientHeight', { value: port });
      }}
    >
      <table
        ref={node => {
          tableRef.current = node;
          if (node)
            Object.defineProperty(node, 'offsetHeight', { value: table });
        }}
      />
      <output data-testid="room">{room}</output>
    </div>
  );
}

afterEach(cleanup);

describe('useRoomBelowRows', () => {
  it('gives the room the rows leave in a taller port', () => {
    const { getByTestId } = render(<Port port={600} table={240} />);
    expect(getByTestId('room').textContent).toBe('360');
  });

  it('gives none while the rows fill the port', () => {
    const { getByTestId } = render(<Port port={600} table={900} />);
    expect(getByTestId('room').textContent).toBe('0');
  });

  it('gives none where it is switched off', () => {
    const { getByTestId } = render(
      <Port port={600} table={240} enabled={false} />,
    );
    expect(getByTestId('room').textContent).toBe('0');
  });
});
