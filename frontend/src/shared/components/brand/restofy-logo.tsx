import Image from "next/image";

const LOGO_WIDTH = 1598;
const LOGO_HEIGHT = 513;

type RestofyLogoProps = Readonly<{ className?: string }>;

/** Both logo variants are rendered; the theme tokens show the one that suits the active theme. */
export function RestofyLogo({ className = "" }: RestofyLogoProps) {
  return (
    <>
      <Image
        className={`theme-only-light ${className}`}
        src="/brand/restofy-logo-light.svg"
        alt="Restofy"
        width={LOGO_WIDTH}
        height={LOGO_HEIGHT}
        priority
        unoptimized
      />
      <Image
        className={`theme-only-dark ${className}`}
        src="/brand/restofy-logo-dark.svg"
        alt="Restofy"
        width={LOGO_WIDTH}
        height={LOGO_HEIGHT}
        priority
        unoptimized
      />
    </>
  );
}
