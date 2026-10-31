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

import { Link, NavLink, Outlet, type RouteObject } from "react-router";
import { StoreIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { cn } from "@/lib/utils";
import { USERS } from "@/shop/session";
import { useSession } from "@/shop/session";
import { useLoad, useTitle } from "@/shop/useLoad";
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

/** How many items the buyer's basket holds, read again whenever this page changes it. */
function useBasketCount(): number | undefined {
  const { user, clients, basketRevision } = useSession();
  const [count] = useLoad(
    async (signal) =>
      user.role !== "buyer"
        ? 0
        : ((await clients.basket.basket.state.findOwn({ signal }))?.state.items ?? []).reduce(
            (sum, item) => sum + item.quantity,
            0,
          ),
    `basket-count:${user.id}:${basketRevision}`,
  );
  return count.status === "loaded" ? count.value : undefined;
}

/** A path no route has. */
function NotFoundPage() {
  useTitle("Not found");
  return (
    <Empty>
      <EmptyHeader>
        <EmptyTitle>Page not found</EmptyTitle>
        <EmptyDescription>
          The shop has no page here. <Link to="/">Browse the shop</Link>.
        </EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}

function Shell() {
  const { user } = useSession();
  const basketCount = useBasketCount();
  const links = [
    { to: "/", label: "Shop", end: true },
    ...(user.role === "buyer"
      ? [
          {
            to: "/basket",
            label: "Basket",
            count: basketCount ? basketCount : undefined,
          },
        ]
      : []),
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
              {"count" in link && link.count !== undefined && (
                // The link keeps its name; the count is what the basket page lists.
                <Badge variant="secondary" className="ml-1" aria-hidden data-testid="basket-count">
                  {link.count}
                </Badge>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="ml-auto">
          <UserSwitcher />
        </div>
      </header>
      {/* Each user's pages start afresh: nothing another user did shows (a checkout's progress, a problem). */}
      <main key={user.id}>
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
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];
