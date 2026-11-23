
import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/shared/lib/i18n/request.ts");

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  reactCompiler: true,

  /**
   * The browser only talks to this origin. /api requests are forwarded to the API Gateway on the server, so no
   * cross-origin request (and no CORS preflight, which the Gateway does not answer) ever happens.
   */
  async rewrites() {
    const gateway = (process.env.API_GATEWAY_URL ?? "http://localhost:5050").replace(/\/+$/, "");
    return [{ source: "/api/:path*", destination: `${gateway}/api/:path*` }];
  },

  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default withNextIntl(nextConfig);
