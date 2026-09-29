import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

const alertVariants = cva(
  "fve:group/alert fve:relative fve:grid fve:w-full fve:gap-0.5 fve:rounded-lg fve:border fve:px-2.5 fve:py-2 fve:text-left fve:text-sm fve:has-data-[slot=alert-action]:relative fve:has-data-[slot=alert-action]:pr-18 fve:has-[>svg]:grid-cols-[auto_1fr] fve:has-[>svg]:gap-x-2 fve:*:[svg]:row-span-2 fve:*:[svg]:translate-y-0.5 fve:*:[svg]:text-current fve:*:[svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "fve:bg-card fve:text-card-foreground",
        destructive:
          "fve:bg-card fve:text-destructive fve:*:data-[slot=alert-description]:text-destructive/90 fve:*:[svg]:text-current",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function Alert({
  className,
  variant,
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof alertVariants>) {
  return (
    <div
      data-slot="alert"
      role="alert"
      className={cn(alertVariants({ variant }), className)}
      {...props}
    />
  )
}

function AlertTitle({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-title"
      className={cn(
        "fve:font-medium fve:group-has-[>svg]/alert:col-start-2 fve:[&_a]:underline fve:[&_a]:underline-offset-3 fve:[&_a]:hover:text-foreground",
        className
      )}
      {...props}
    />
  )
}

function AlertDescription({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-description"
      className={cn(
        "fve:text-sm fve:text-balance fve:text-muted-foreground fve:md:text-pretty fve:[&_a]:underline fve:[&_a]:underline-offset-3 fve:[&_a]:hover:text-foreground fve:[&_p:not(:last-child)]:mb-4",
        className
      )}
      {...props}
    />
  )
}

function AlertAction({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-action"
      className={cn("fve:absolute fve:top-2 fve:right-2", className)}
      {...props}
    />
  )
}

export { Alert, AlertTitle, AlertDescription, AlertAction }
