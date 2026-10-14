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

import { NavLink, Outlet, type RouteObject } from "react-router";
import { StoreIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { USERS } from "@/shop/session";
import { useSession } from "@/shop/session";
import { AdminPage } from "@/pages/AdminPage";
import { BasketPage } from "@/pages/BasketPage";
import { OrderPage } from "@/pages/OrderPage";
import { OrdersPage } from "@/pages/OrdersPage";
import { ShopPage } from "@/pages/ShopPage";

function UserSwitcher() {
  const { user, switchTo } = useSession();
  return (
    <label className="flex items-center gap-2 text-sm">
      Acting as
      <select
        aria-label="User"
        className="h-8 rounded-lg border border-input bg-background px-2 text-sm"
        value={user.id}
        onChange={(event) => {
          const next = USERS.find((candidate) => candidate.id === event.target.value);
          if (next) switchTo(next);
        }}
      >
        {USERS.map((candidate) => (
          <option key={candidate.id} value={candidate.id}>
            {candidate.id} ({candidate.role})
          </option>
        ))}
      </select>
    </label>
  );
}

function Shell() {
  const { user } = useSession();
  const links = [
    { to: "/", label: "Shop", end: true },
    ...(user.role === "buyer" ? [{ to: "/basket", label: "Basket" }] : []),
    { to: "/orders", label: "Orders" },
    ...(user.role === "employee" ? [{ to: "/admin", label: "Catalog admin" }] : []),
  ];
  return (
    <div className="mx-auto flex min-h-svh max-w-6xl flex-col gap-4 p-4">
      <header className="flex flex-wrap items-center gap-4 border-b pb-3">
        <span className="flex items-center gap-2 font-semibold">
          <StoreIcon aria-hidden />
          Wow eShop
        </span>
        <nav className="flex gap-1" aria-label="Main">
          {links.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.end}
              className={({ isActive }) =>
                cn(
                  "rounded-lg px-2.5 py-1 text-sm",
                  isActive ? "bg-muted font-medium" : "text-muted-foreground",
                )
              }
            >
              {link.label}
            </NavLink>
          ))}
        </nav>
        <div className="ml-auto">
          <UserSwitcher />
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}

/** The UI's routes. */
export const routes: RouteObject[] = [
  {
    element: <Shell />,
    children: [
      { index: true, element: <ShopPage /> },
      { path: "basket", element: <BasketPage /> },
      { path: "orders", element: <OrdersPage /> },
      { path: "orders/:id", element: <OrderPage /> },
      { path: "admin", element: <AdminPage /> },
    ],
  },
];
