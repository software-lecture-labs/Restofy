import qrcode from "qrcode-generator";

type OtpauthQrProps = {
  /** The otpauth:// address of the enrolment. It is drawn in the browser and never sent anywhere. */
  value: string;
  label: string;
};

/** Quiet zone the QR specification asks for around the code, in modules. */
const QUIET_ZONE = 4;

/** Dark squares of the code as one SVG path, so no markup string has to be injected into the page. */
function buildPath(value: string): { path: string; size: number } | undefined {
  try {
    const qr = qrcode(0, "M");
    qr.addData(value);
    qr.make();
    const count = qr.getModuleCount();
    let path = "";
    for (let row = 0; row < count; row += 1) {
      for (let col = 0; col < count; col += 1) {
        if (qr.isDark(row, col)) path += `M${col + QUIET_ZONE} ${row + QUIET_ZONE}h1v1h-1z`;
      }
    }
    return { path, size: count + QUIET_ZONE * 2 };
  } catch {
    // Data too long for any QR version: the typed secret below is still there.
    return undefined;
  }
}

/** A scannable QR code with a fixed white background, so any authenticator app can read it in dark theme too. */
export function OtpauthQr({ value, label }: OtpauthQrProps) {
  const code = buildPath(value);
  if (!code) return null;

  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${code.size} ${code.size}`}
      shapeRendering="crispEdges"
      className="mx-auto mt-3 block size-44 rounded-2xl border border-line bg-white p-2"
    >
      <path d={code.path} fill="#000" />
    </svg>
  );
}
