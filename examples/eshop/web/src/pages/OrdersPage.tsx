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

import { useState } from "react";
import { Link } from "react-router";
import { TruckIcon } from "lucide-react";
import { desc, filter } from "@ahoo-wang/wow-client/dsl";
import type { OrderState } from "@/generated/ordering/eshop-ordering";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Spinner } from "@/components/ui/spinner";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad, useTitle } from "@/shop/useLoad";

/** An order's status as a badge. */
export function OrderStatus({ status }: { status: OrderState["status"] }) {
  const variant =
    status === "CANCELLED"
      ? "destructive"
      : status === "PAID" || status === "SHIPPED"
        ? "default"
        : "secondary";
  return (
    <Badge variant={variant} data-testid="order-status">
      {status}
    </Badge>
  );
}

/**
 * The orders the caller may read, newest first: a buyer's own (the ordering
 * service scopes the query to its owner), every order for an employee, who
 * ships the paid ones.
 */
export function OrdersPage() {
  useTitle("Orders");
  const { user, clients } = useSession();
  const [orders, reload] = useLoad(
    (signal) =>
      clients.ordering.order.snapshots.list(
        {
          filter: filter.matchAll(),
          sort: [desc("firstEventTime")],
          limit: 50,
        },
        { signal },
      ),
    `orders:${user.id}`,
  );
  const [problem, setProblem] = useState<unknown>();
  // The order being shipped: its button is busy, so a second press sends nothing.
  const [shipping, setShipping] = useState<string>();

  async function ship(id: string) {
    setProblem(undefined);
    setShipping(id);
    try {
      await clients.ordering.order.commands.shipOrder(id);
    } catch (error) {
      setProblem(error);
    } finally {
      setShipping(undefined);
    }
    reload();
  }

  return (
    <div className="flex flex-col gap-4">
      {problem !== undefined && <ProblemAlert error={problem} service="ordering" />}
      {orders.status === "loading" && <Skeleton className="h-32 w-full" />}
      {orders.status === "failed" && (
        <ProblemAlert error={orders.error} service="ordering" onRetry={reload} />
      )}
      {orders.status === "loaded" && orders.value.length === 0 && (
        <Empty>
          <EmptyHeader>
            <EmptyTitle>No orders</EmptyTitle>
            <EmptyDescription>
              {user.role === "buyer"
                ? "Check a basket out to place one."
                : "No buyer has placed an order yet."}
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      )}
      {orders.status === "loaded" && orders.value.length > 0 && (
        <Table aria-label="Orders">
          <TableHeader>
            <TableRow>
              <TableHead>Order</TableHead>
              <TableHead>Buyer</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Items</TableHead>
              <TableHead className="text-right">Total</TableHead>
              {user.role === "employee" && <TableHead />}
            </TableRow>
          </TableHeader>
          <TableBody>
            {orders.value.map((order) => (
              <TableRow key={order.aggregateId} data-testid="order">
                <TableCell>
                  <Link to={`/orders/${encodeURIComponent(order.aggregateId)}`}>
                    {order.aggregateId}
                  </Link>
                </TableCell>
                <TableCell>{order.ownerId}</TableCell>
                <TableCell>
                  <OrderStatus status={order.state.status} />
                </TableCell>
                <TableCell>
                  {order.state.items.reduce((sum, item) => sum + item.quantity, 0)}
                </TableCell>
                <TableCell className="text-right">
                  {money(order.state.totalAmount)}
                </TableCell>
                {user.role === "employee" && (
                  <TableCell>
                    {order.state.status === "PAID" && (
                      <Button
                        size="sm"
                        onClick={() => ship(order.aggregateId)}
                        disabled={shipping === order.aggregateId}
                        aria-label={`Ship ${order.aggregateId}`}
                      >
                        {shipping === order.aggregateId ? (
                          <Spinner data-icon="inline-start" />
                        ) : (
                          <TruckIcon data-icon="inline-start" />
                        )}
                        Ship
                      </Button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
