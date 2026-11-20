import { useId, type ComponentProps, type ReactNode } from "react";

type TextFieldProps = Omit<ComponentProps<"input">, "id"> & {
  label: string;
  error?: string;
  endAdornment?: ReactNode;
};

export function TextField({ label, error, endAdornment, className = "", ...props }: TextFieldProps) {
  const id = useId();
  const errorId = `${id}-error`;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="relative">
        <input
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className={`peer h-14 w-full rounded-2xl border bg-surface-muted px-4 pb-1 pt-5 text-base text-fg placeholder-transparent transition-[border-color,box-shadow] duration-300 focus:border-brand focus:outline-none focus:shadow-[0_0_0_4px_rgb(var(--glow)/0.16),0_8px_24px_-10px_rgb(var(--glow)/0.55)] ${error ? "border-danger" : "border-line"} ${endAdornment ? "pr-12" : ""} ${className}`}
          {...props}
          placeholder=" "
        />
        <label
          htmlFor={id}
          className="pointer-events-none absolute left-4 top-1/2 max-w-[calc(100%-2rem)] origin-left -translate-y-1/2 truncate text-base text-fg-muted transition-[translate,scale,color] duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] peer-focus:-translate-y-[calc(50%+0.95rem)] peer-focus:scale-[0.8] peer-focus:font-semibold peer-focus:text-brand-text peer-[:not(:placeholder-shown)]:-translate-y-[calc(50%+0.95rem)] peer-[:not(:placeholder-shown)]:scale-[0.8] peer-[:not(:placeholder-shown)]:font-semibold peer-[:not(:placeholder-shown)]:text-brand-text motion-reduce:transition-none"
        >
          {label}
        </label>
        {endAdornment ? (
          <div className="absolute inset-y-0 right-1.5 flex items-center">{endAdornment}</div>
        ) : null}
      </div>
      {error ? (
        <p id={errorId} role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
