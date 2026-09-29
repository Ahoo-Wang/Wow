"use client"

import { useMemo } from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

import { Label } from "@/ui/components/label"
import { Separator } from "@/ui/components/separator"

function FieldSet({ className, ...props }: React.ComponentProps<"fieldset">) {
  return (
    <fieldset
      data-slot="field-set"
      className={cn(
        "fve:flex fve:flex-col fve:gap-4 fve:has-[>[data-slot=checkbox-group]]:gap-3 fve:has-[>[data-slot=radio-group]]:gap-3",
        className
      )}
      {...props}
    />
  )
}

function FieldLegend({
  className,
  variant = "legend",
  ...props
}: React.ComponentProps<"legend"> & { variant?: "legend" | "label" }) {
  return (
    <legend
      data-slot="field-legend"
      data-variant={variant}
      className={cn(
        "fve:mb-1.5 fve:font-medium fve:data-[variant=label]:text-sm fve:data-[variant=legend]:text-base",
        className
      )}
      {...props}
    />
  )
}

function FieldGroup({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="field-group"
      className={cn(
        "fve:group/field-group fve:@container/field-group fve:flex fve:w-full fve:flex-col fve:gap-5 fve:data-[slot=checkbox-group]:gap-3 fve:*:data-[slot=field-group]:gap-4",
        className
      )}
      {...props}
    />
  )
}

const fieldVariants = cva(
  "fve:group/field fve:flex fve:w-full fve:gap-2 fve:data-[invalid=true]:text-destructive",
  {
    variants: {
      orientation: {
        vertical: "fve:flex-col fve:*:w-full fve:[&>[class~='fve:sr-only']]:w-auto",
        horizontal:
          "fve:flex-row fve:items-center fve:has-[>[data-slot=field-content]]:items-start fve:*:data-[slot=field-label]:flex-auto fve:has-[>[data-slot=field-content]]:[&>[role=checkbox],[role=radio]]:mt-px",
        responsive:
          "fve:flex-col fve:*:w-full fve:@md/field-group:flex-row fve:@md/field-group:items-center fve:@md/field-group:*:w-auto fve:@md/field-group:has-[>[data-slot=field-content]]:items-start fve:@md/field-group:*:data-[slot=field-label]:flex-auto fve:[&>[class~='fve:sr-only']]:w-auto fve:@md/field-group:has-[>[data-slot=field-content]]:[&>[role=checkbox],[role=radio]]:mt-px",
      },
    },
    defaultVariants: {
      orientation: "vertical",
    },
  }
)

function Field({
  className,
  orientation = "vertical",
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof fieldVariants>) {
  return (
    <div
      role="group"
      data-slot="field"
      data-orientation={orientation}
      className={cn(fieldVariants({ orientation }), className)}
      {...props}
    />
  )
}

function FieldContent({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="field-content"
      className={cn(
        "fve:group/field-content fve:flex fve:flex-1 fve:flex-col fve:gap-0.5 fve:leading-snug",
        className
      )}
      {...props}
    />
  )
}

function FieldLabel({
  className,
  ...props
}: React.ComponentProps<typeof Label>) {
  return (
    <Label
      data-slot="field-label"
      className={cn(
        "fve:group/field-label fve:peer/field-label fve:flex fve:w-fit fve:gap-2 fve:leading-snug fve:group-data-[disabled=true]/field:opacity-50 fve:has-data-checked:border-primary/30 fve:has-data-checked:bg-primary/5 fve:has-[>[data-slot=field]]:rounded-lg fve:has-[>[data-slot=field]]:border fve:has-[>[data-slot=field]]:not-has-[:disabled,[data-disabled]]:hover:bg-muted/50 fve:has-[>[data-slot=field]]:has-[:focus-visible]:border-ring fve:has-[>[data-slot=field]]:has-[:focus-visible]:ring-3 fve:has-[>[data-slot=field]]:has-[:focus-visible]:ring-ring/50 fve:*:data-[slot=field]:p-2.5 fve:dark:has-data-checked:border-primary/20 fve:dark:has-data-checked:bg-primary/10",
        "fve:has-[>[data-slot=field]]:w-full fve:has-[>[data-slot=field]]:flex-col",
        className
      )}
      {...props}
    />
  )
}

function FieldTitle({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="field-label"
      className={cn(
        "fve:flex fve:w-fit fve:items-center fve:gap-2 fve:text-sm fve:font-medium fve:group-data-[disabled=true]/field:opacity-50",
        className
      )}
      {...props}
    />
  )
}

function FieldDescription({ className, ...props }: React.ComponentProps<"p">) {
  return (
    <p
      data-slot="field-description"
      className={cn(
        "fve:text-left fve:text-sm fve:leading-normal fve:font-normal fve:text-muted-foreground fve:group-has-data-horizontal/field:text-balance fve:[[data-variant=legend]+&]:-mt-1.5",
        "fve:last:mt-0 fve:nth-last-2:-mt-1",
        "fve:[&>a]:underline fve:[&>a]:underline-offset-4 fve:[&>a:hover]:text-primary",
        className
      )}
      {...props}
    />
  )
}

function FieldSeparator({
  children,
  className,
  ...props
}: React.ComponentProps<"div"> & {
  children?: React.ReactNode
}) {
  return (
    <div
      data-slot="field-separator"
      data-content={!!children}
      className={cn(
        "fve:relative fve:-my-2 fve:h-5 fve:text-sm fve:group-data-[variant=outline]/field-group:-mb-2",
        className
      )}
      {...props}
    >
      <Separator className="fve:absolute fve:inset-0 fve:top-1/2" />
      {children && (
        <span
          className="fve:relative fve:mx-auto fve:block fve:w-fit fve:bg-background fve:px-2 fve:text-muted-foreground"
          data-slot="field-separator-content"
        >
          {children}
        </span>
      )}
    </div>
  )
}

function FieldError({
  className,
  children,
  errors,
  ...props
}: React.ComponentProps<"div"> & {
  errors?: Array<{ message?: string } | undefined>
}) {
  const content = useMemo(() => {
    if (children) {
      return children
    }

    if (!errors?.length) {
      return null
    }

    const uniqueErrors = [
      ...new Map(errors.map((error) => [error?.message, error])).values(),
    ]

    if (uniqueErrors?.length == 1) {
      return uniqueErrors[0]?.message
    }

    return (
      <ul className="fve:ml-4 fve:flex fve:list-disc fve:flex-col fve:gap-1">
        {uniqueErrors.map(
          (error, index) =>
            error?.message && <li key={index}>{error.message}</li>
        )}
      </ul>
    )
  }, [children, errors])

  if (!content) {
    return null
  }

  return (
    <div
      role="alert"
      data-slot="field-error"
      className={cn("fve:text-sm fve:font-normal fve:text-destructive", className)}
      {...props}
    >
      {content}
    </div>
  )
}

export {
  Field,
  FieldLabel,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLegend,
  FieldSeparator,
  FieldSet,
  FieldContent,
  FieldTitle,
}
