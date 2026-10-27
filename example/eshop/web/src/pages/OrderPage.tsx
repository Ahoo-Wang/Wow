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
import { useParams } from "react-router";
import { RefreshCwIcon, TruckIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ProblemAlert } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad } from "@/shop/useLoad";
import { OrderStatus } from "./OrdersPage";

/** An order, its items and its payment; an employee ships it once paid. */
export function OrderPage() {
  const { id = "" } = useParams();
  const { user, clients } = useSession();
  const [order, reload] = useLoad(
    async (signal) => {
      const [view, payment] = await Promise.all([
        clients.ordering.order.state.get(id, { signal }),
        // The payment service creates the payment once the stock is confirmed.
        clients.payment.payment.state.find(id, { signal }),
      ]);
      return { view, payment };
    },
    `order:${user.id}:${id}`,
  );
  const [problem, setProblem] = useState<unknown>();

  async function ship() {
    setProblem(undefined);
    try {
      await clients.ordering.order.commands.shipOrder(id);
    } catch (error) {
      setProblem(error);
    }
    reload();
  }

  if (order.status === "loading") return <Skeleton className="h-48 w-full" />;
  if (order.status === "failed") return <ProblemAlert error={order.error} />;
  const { view, payment } = order.value;
  return (
    <div className="flex flex-col gap-4">
      {problem !== undefined && <ProblemAlert error={problem} />}
      <Card>
        <CardHeader>
          <CardTitle>Order {id}</CardTitle>
          <CardDescription>
            Bought by {view.scope.ownerId ?? "anonymous"}, version{" "}
            {view.version}
          </CardDescription>
          <CardAction className="flex items-center gap-2">
            <OrderStatus status={view.state.status} />
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="Refresh"
              onClick={reload}
            >
              <RefreshCwIcon />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Table aria-label="Order items">
            <TableHeader>
              <TableRow>
                <TableHead>Product</TableHead>
                <TableHead>Quantity</TableHead>
                <TableHead>Unit price</TableHead>
                <TableHead>Stock</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {view.state.items.map((item) => (
                <TableRow key={item.productId}>
                  <TableCell>{item.productId}</TableCell>
                  <TableCell>{item.quantity}</TableCell>
                  <TableCell>{money(item.unitPrice)}</TableCell>
                  <TableCell>
                    {view.state.confirmedProducts.includes(item.productId)
                      ? "Confirmed"
                      : "Not confirmed"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="text-sm">
            Total: <strong>{money(view.state.totalAmount)}</strong>
          </p>
          {view.state.cancelReason && (
            <p className="text-sm text-destructive">
              Cancelled: {view.state.cancelReason}
            </p>
          )}
          <p className="flex items-center gap-2 text-sm" data-testid="payment">
            Payment:{" "}
            {payment ? (
              <>
                <Badge variant="outline">{payment.state.status}</Badge>
                {money(payment.state.amount)}
                {payment.state.reason && ` — ${payment.state.reason}`}
              </>
            ) : (
              "not requested yet"
            )}
          </p>
          {user.role === "employee" && view.state.status === "PAID" && (
            <div>
              <Button onClick={ship}>
                <TruckIcon data-icon="inline-start" />
                Ship the order
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
