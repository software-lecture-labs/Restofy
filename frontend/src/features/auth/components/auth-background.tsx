/** Purely decorative layers behind the login card: a soft tint, a faint dot grid and fine grain. */
export function AuthBackground() {
  return (
    <div
      aria-hidden="true"
      className="login-bg pointer-events-none fixed inset-0 -z-10 overflow-hidden"
    >
      <div className="login-dots" />
      <div className="login-grain" />
    </div>
  );
}
