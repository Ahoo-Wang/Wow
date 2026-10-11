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

import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { jwt } from '../cosec.test.ts';
import { currentUser } from '../cosec.ts';
import LoginPage from './LoginPage.tsx';

describe('LoginPage', () => {
  it('signs in with the pasted token and goes home', () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );
    expect(screen.getByRole('button', { name: '登录' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('访问令牌'), {
      target: { value: `Bearer ${jwt({ sub: 'ann', tenantId: 't1' })}` },
    });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));
    expect(currentUser()).toMatchObject({ id: 'ann', tenantId: 't1' });
    expect(assign).toHaveBeenCalledWith('/');
    vi.unstubAllGlobals();
  });
});
