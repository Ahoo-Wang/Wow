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
import { Link, useParams } from "react-router";
import { ArrowLeftIcon, RefreshCwIcon, TruckIcon } from "lucide-react";
import type { OrderState } from "@/generated/ordering/eshop-ordering";
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
import { Spinner } from "@/components/ui/spinner";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { nameOf, useProductNames } from "@/shop/catalog";
import { ProblemAlert, problemOf } from "@/shop/problems";
import { useSession } from "@/shop/session";
import { money, useLoad, useTitle } from "@/shop/useLoad";
import { OrderStatus } from "./OrdersPage";

/** An item's stock as the order's state says it: reserved for the order, released once it is cancelled. */
function stockOf(state: OrderState, productId: string): string {
  const confirmed = state.confirmedProducts.includes(productId);
  if (state.status === "CANCELLED") return confirmed ? "Released" : "Not reserved";
  return confirmed ? "Confirmed" : "Not confirmed";
}

/**
 * An order, its items and its payment; an employee ships it once paid. The
 * order and its payment are read apart: a payment service that does not
 * answer leaves the order shown, with its payment unknown.
 */
export function OrderPage() {
  const { id = "" } = useParams();
  useTitle(`Order ${id}`);
  const { user, clients } = useSession();
  const [order, reload] = useLoad(
    (signal) => clients.ordering.order.state.get(id, { signal }),
    `order:${user.id}:${id}`,
  );
  const [payment, reloadPayment] = useLoad(
    // The payment service creates the payment once the stock is confirmed.
    (signal) => clients.payment.payment.state.find(id, { signal }),
    `payment:${user.id}:${id}`,
  );
  const items = order.status === "loaded" ? order.value.state.items : [];
  const names = useProductNames(items.map((item) => item.productId));
  const [problem, setProblem] = useState<unknown>();
  const [shipping, setShipping] = useState(false);

  async function ship() {
    setProblem(undefined);
    setShipping(true);
    try {
      await clients.ordering.order.commands.shipOrder(id);
    } catch (error) {
      setProblem(error);
    } finally {
      setShipping(false);
    }
    reload();
    reloadPayment();
  }

  const back = (
    <Link to="/orders" className="flex items-center gap-1 text-sm">
      <ArrowLeftIcon aria-hidden className="size-4" />
      Back to orders
    </Link>
  );
  if (order.status === "loading") return <Skeleton className="h-48 w-full" />;
  if (order.status === "failed")
    return (
      <div className="flex flex-col gap-4">
        {back}
        <ProblemAlert error={order.error} service="ordering" onRetry={reload} />
      </div>
    );
  const view = order.value;
  const cancelled = view.state.status === "CANCELLED";
  return (
    <div className="flex flex-col gap-4">
      {back}
      {problem !== undefined && <ProblemAlert error={problem} service="ordering" />}
      <Card>
        <CardHeader>
          <CardTitle>Order {id}</CardTitle>
          <CardDescription>
            Bought by {view.ownerId ?? "anonymous"}, version{" "}
            {view.version}
          </CardDescription>
          <CardAction className="flex items-center gap-2">
            <OrderStatus status={view.state.status} />
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="Refresh"
              onClick={() => {
                reload();
                reloadPayment();
              }}
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
                  <TableCell>{nameOf(names, item.productId)}</TableCell>
                  <TableCell>{item.quantity}</TableCell>
                  <TableCell>{money(item.unitPrice)}</TableCell>
                  <TableCell>{stockOf(view.state, item.productId)}</TableCell>
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
          <p className="flex flex-wrap items-center gap-2 text-sm" data-testid="payment">
            Payment:{" "}
            {payment.status === "loading" && "…"}
            {payment.status === "failed" && (
              <span className="text-destructive">
                unavailable ({problemOf(payment.error, "payment").title})
              </span>
            )}
            {payment.status === "loaded" &&
              (payment.value ? (
                <>
                  <Badge variant="outline">{payment.value.state.status}</Badge>
                  {money(payment.value.state.amount)}
                  {payment.value.state.reason && ` — ${payment.value.state.reason}`}
                </>
              ) : cancelled ? (
                "none: the order was cancelled before its payment"
              ) : (
                "not requested yet"
              ))}
          </p>
          {payment.status === "failed" && (
            <ProblemAlert error={payment.error} service="payment" onRetry={reloadPayment} />
          )}
          {user.role === "employee" && view.state.status === "PAID" && (
            <div>
              <Button onClick={ship} disabled={shipping}>
                {shipping ? (
                  <Spinner data-icon="inline-start" />
                ) : (
                  <TruckIcon data-icon="inline-start" />
                )}
                Ship the order
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
