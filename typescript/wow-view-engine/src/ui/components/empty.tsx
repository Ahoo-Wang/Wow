import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

function Empty({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="empty"
      className={cn(
        "fve:flex fve:w-full fve:min-w-0 fve:flex-1 fve:flex-col fve:items-center fve:justify-center fve:gap-4 fve:rounded-xl fve:border-dashed fve:p-6 fve:text-center fve:text-balance",
        className
      )}
      {...props}
    />
  )
}

function EmptyHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="empty-header"
      className={cn("fve:flex fve:max-w-sm fve:flex-col fve:items-center fve:gap-2", className)}
      {...props}
    />
  )
}

const emptyMediaVariants = cva(
  "fve:mb-2 fve:flex fve:shrink-0 fve:items-center fve:justify-center fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "fve:bg-transparent",
        icon: "fve:flex fve:size-8 fve:shrink-0 fve:items-center fve:justify-center fve:rounded-lg fve:bg-muted fve:text-foreground fve:[&_svg:not([class*='size-'])]:size-4",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function EmptyMedia({
  className,
  variant = "default",
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof emptyMediaVariants>) {
  return (
    <div
      data-slot="empty-icon"
      data-variant={variant}
      className={cn(emptyMediaVariants({ variant, className }))}
      {...props}
    />
  )
}

function EmptyTitle({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="empty-title"
      className={cn(
        "fve:text-sm fve:font-medium fve:tracking-tight",
        className
      )}
      {...props}
    />
  )
}

function EmptyDescription({ className, ...props }: React.ComponentProps<"p">) {
  return (
    <div
      data-slot="empty-description"
      className={cn(
        "fve:text-sm/relaxed fve:text-muted-foreground fve:[&>a]:underline fve:[&>a]:underline-offset-4 fve:[&>a:hover]:text-primary",
        className
      )}
      {...props}
    />
  )
}

function EmptyContent({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="empty-content"
      className={cn(
        "fve:flex fve:w-full fve:max-w-sm fve:min-w-0 fve:flex-col fve:items-center fve:gap-2.5 fve:text-sm fve:text-balance",
        className
      )}
      {...props}
    />
  )
}

export {
  Empty,
  EmptyHeader,
  EmptyTitle,
  EmptyDescription,
  EmptyContent,
  EmptyMedia,
}
