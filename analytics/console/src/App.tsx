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

import { Link, NavLink, Navigate, Outlet } from 'react-router';
import { currentUser, LOGIN_PATH, signOut, type CurrentUser } from './cosec.ts';
import { ADMIN_PATH, BOARDS_PATH, HOME_PATH } from './routes.ts';

/**
 * The console's shell: the places, who is signed in, and the page. Nobody
 * signed in goes to the sign-in page; the admin place shows only to the
 * registry's admins (the server enforces it either way).
 */
export default function App({
  user = currentUser(),
}: {
  user?: CurrentUser | null;
}) {
  if (!user) return <Navigate to={LOGIN_PATH} replace />;
  return (
    <div className="shell fve-tokens">
      <header>
        <Link to={HOME_PATH} className="brand">
          Wow 分析
        </Link>
        <nav aria-label="位置">
          <NavLink to={HOME_PATH} end>
            数据集
          </NavLink>
          <NavLink to={BOARDS_PATH}>看板</NavLink>
          {user.admin ? <NavLink to={ADMIN_PATH}>数据集管理</NavLink> : null}
        </nav>
        <span className="user">
          {user.id}
          {user.tenantId ? ` · ${user.tenantId}` : ''}
        </span>
        <button
          type="button"
          onClick={() => {
            signOut();
            window.location.assign(LOGIN_PATH);
          }}
        >
          退出
        </button>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
