import type { ReactNode } from "react";

/** The rounded card with the glowing rim; the glow itself lives in login.css. */
export function LoginCard({ children }: { children: ReactNode }) {
  return (
    <div className="login-card w-full max-w-md">
      <div className="login-card-surface relative z-10 rounded-[inherit] bg-surface p-6 sm:p-9">
        {children}
      </div>
    </div>
  );
}
