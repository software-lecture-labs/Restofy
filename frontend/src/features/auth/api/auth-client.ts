import { ApiError, gatewayRequest } from "@/shared/lib/api/gateway";

export type StaffSession = {
  status: "AUTHENTICATED" | "MFA_PENDING";
  role: "ADMIN" | "WAITER" | "KITCHEN";
  idleExpiresAt: string;
  absoluteExpiresAt: string;
  mfaSetupRequired?: boolean | null;
};

async function csrf(): Promise<string> {
  const response = await gatewayRequest("/api/v1/auth/csrf");
  const body: unknown = await response.json();
  if (!body || typeof body !== "object" || !("token" in body) || typeof body.token !== "string" || !body.token) {
    throw new ApiError(502);
  }
  return body.token;
}

function readSession(body: unknown): StaffSession {
  if (!body || typeof body !== "object" || !("status" in body) || !("role" in body)
    || !["AUTHENTICATED", "MFA_PENDING"].includes(String(body.status))
    || !["ADMIN", "WAITER", "KITCHEN"].includes(String(body.role))
    || !("idleExpiresAt" in body) || typeof body.idleExpiresAt !== "string"
    || !("absoluteExpiresAt" in body) || typeof body.absoluteExpiresAt !== "string"
    || !Number.isFinite(Date.parse(body.idleExpiresAt)) || !Number.isFinite(Date.parse(body.absoluteExpiresAt))
    || (body.status === "MFA_PENDING" && body.role !== "ADMIN")
    || ("mfaSetupRequired" in body && body.mfaSetupRequired !== null && typeof body.mfaSetupRequired !== "boolean")) {
    throw new ApiError(502);
  }
  return body as StaffSession;
}

async function session(): Promise<StaffSession> {
  return readSession(await (await gatewayRequest("/api/v1/auth/session")).json());
}

export const authClient = {
  async login(email: string, password: string): Promise<StaffSession> {
    const token = await csrf();
    // A lost acknowledgement is uncertain: neither replay the POST nor claim another existing session is this login.
    await gatewayRequest("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
      body: JSON.stringify({ email, password }),
    });
    // Login changes the CSRF credential binding. Do not read the narrow-path cookie from JS.
    await csrf();
    return session();
  },
  async logout(): Promise<void> {
    const token = await csrf();
    await gatewayRequest("/api/v1/auth/logout", {
      method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
    });
  },
};
