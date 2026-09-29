import * as React from "react"
import { cn } from "cn"

function Card({
  className,
  size = "default",
  ...props
}: React.ComponentProps<"div"> & { size?: "default" | "sm" }) {
  return (
    <div
      data-slot="card"
      data-size={size}
      className={cn(
        "fve:group/card fve:flex fve:flex-col fve:gap-(--card-spacing) fve:overflow-hidden fve:rounded-xl fve:bg-card fve:py-(--card-spacing) fve:text-sm fve:text-card-foreground fve:ring-1 fve:ring-foreground/10 fve:[--card-spacing:--spacing(4)] fve:has-data-[slot=card-footer]:pb-0 fve:has-[>img:first-child]:pt-0 fve:data-[size=sm]:[--card-spacing:--spacing(3)] fve:data-[size=sm]:has-data-[slot=card-footer]:pb-0 fve:*:[img:first-child]:rounded-t-xl fve:*:[img:last-child]:rounded-b-xl",
        className
      )}
      {...props}
    />
  )
}

function CardHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-header"
      className={cn(
        "fve:group/card-header fve:@container/card-header fve:grid fve:auto-rows-min fve:items-start fve:gap-1 fve:rounded-t-xl fve:px-(--card-spacing) fve:has-data-[slot=card-action]:grid-cols-[1fr_auto] fve:has-data-[slot=card-description]:grid-rows-[auto_auto] fve:[&[class~='fve:border-b']]:pb-(--card-spacing)",
        className
      )}
      {...props}
    />
  )
}

function CardTitle({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-title"
      className={cn(
        "fve:text-base fve:leading-snug fve:font-medium fve:group-data-[size=sm]/card:text-sm",
        className
      )}
      {...props}
    />
  )
}

function CardDescription({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-description"
      className={cn("fve:text-sm fve:text-muted-foreground", className)}
      {...props}
    />
  )
}

function CardAction({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-action"
      className={cn(
        "fve:col-start-2 fve:row-span-2 fve:row-start-1 fve:self-start fve:justify-self-end",
        className
      )}
      {...props}
    />
  )
}

function CardContent({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-content"
      className={cn("fve:px-(--card-spacing)", className)}
      {...props}
    />
  )
}

function CardFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-footer"
      className={cn(
        "fve:flex fve:items-center fve:rounded-b-xl fve:border-t fve:bg-muted/50 fve:p-(--card-spacing)",
        className
      )}
      {...props}
    />
  )
}

export {
  Card,
  CardHeader,
  CardFooter,
  CardTitle,
  CardAction,
  CardDescription,
  CardContent,
}
