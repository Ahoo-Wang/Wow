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

import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { shopClients, type ShopClients } from "./clients";

/**
 * The two caller levels of the eShop (decision D189): a buyer owns its basket
 * and orders; an employee is a caller of the whole tenant, who changes the
 * catalog and ships orders.
 */
export type Role = "buyer" | "employee";

/** Who the UI acts as: the servers read it from `Wow-User`, `Wow-Owner` and `Wow-Roles`. */
export interface User {
  readonly id: string;
  readonly role: Role;
}

/** The users the switcher offers. */
export const USERS: readonly User[] = [
  { id: "alice", role: "buyer" },
  { id: "bob", role: "buyer" },
  { id: "erin", role: "employee" },
];

const STORAGE_KEY = "wow-eshop-web.user";

function storedUser(): User {
  try {
    const id = localStorage.getItem(STORAGE_KEY);
    const user = USERS.find((candidate) => candidate.id === id);
    if (user) return user;
  } catch {
    // No storage: the first user.
  }
  return USERS[0];
}

interface Session {
  readonly user: User;
  readonly clients: ShopClients;
  switchTo(user: User): void;
}

const SessionContext = createContext<Session | undefined>(undefined);

/** Holds the user the UI acts as, and the clients that act as that user. */
export function SessionProvider({
  children,
  initialUser,
  transport,
}: {
  children: ReactNode;
  initialUser?: User;
  /** The `fetch` requests go through (a stub, in tests). */
  transport?: typeof fetch;
}) {
  const [user, setUser] = useState<User>(() => initialUser ?? storedUser());
  const session = useMemo<Session>(
    () => ({
      user,
      clients: shopClients(user, transport),
      switchTo(next) {
        try {
          localStorage.setItem(STORAGE_KEY, next.id);
        } catch {
          // Remembered for this page only.
        }
        setUser(next);
      },
    }),
    [user, transport],
  );
  return (
    <SessionContext.Provider value={session}>
      {children}
    </SessionContext.Provider>
  );
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error("useSession needs a SessionProvider.");
  return session;
}
