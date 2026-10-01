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

/**
 * A refusal on the open view: the catalogue's sentence, and the store's own
 * reason after it — once. `view.write.invalid` says `{reason}` itself, so
 * nothing is added to it; a sentence without one gets the reason appended.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Issue } from '../src/index.js';
import { RefusalLine } from '../src/ui/kit/OutcomeActions.js';

afterEach(cleanup);

const refusal = (code: string): Issue => ({
  code,
  path: [],
  severity: 'error',
  params: { reason: 'managed by operations' },
});

describe('RefusalLine', () => {
  it('says a reason its sentence already carries once', () => {
    render(<RefusalLine issue={refusal('view.write.invalid')} />);
    expect(
      screen.getByText('The server refused this write: managed by operations'),
    ).toBeTruthy();
  });

  it('appends the reason to a sentence without one', () => {
    render(<RefusalLine issue={refusal('view.write.forbidden')} />);
    expect(
      screen.getByText('You may not write to this view. managed by operations'),
    ).toBeTruthy();
  });

  it('keeps a manager row to the sentence', () => {
    render(
      <RefusalLine issue={refusal('view.write.forbidden')} surface="row" />,
    );
    expect(screen.getByText('You may not write to this view.')).toBeTruthy();
  });
});
