import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { ApiError } from "@/shared/lib/api";

const replace = vi.hoisted(() => vi.fn());
const api = vi.hoisted(() => ({ getSession: vi.fn(), logout: vi.fn() }));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));
vi.mock("../api/auth-api", () => api);

import { StaffHome } from "./staff-home";

const session = (overrides = {}) => ({
  status: "AUTHENTICATED",
  role: "WAITER",
  idleExpiresAt: "2030-01-01T10:00:00Z",
  absoluteExpiresAt: "2030-01-01T18:00:00Z",
  ...overrides,
});

beforeEach(() => {
  vi.clearAllMocks();
  api.logout.mockResolvedValue(undefined);
});

describe("StaffHome", () => {
  it("shows the role and the session end once the session is loaded", async () => {
    api.getSession.mockResolvedValue(session());
    renderWithIntl(<StaffHome />);

    expect(await screen.findByText("Garson")).toBeInTheDocument();
    expect(screen.getByText(/sonraki adımlarda eklenecek/)).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("sends a person without a session to the login", async () => {
    api.getSession.mockRejectedValue(new ApiError(401));
    renderWithIntl(<StaffHome />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  });

  it("sends a session that still waits for its second factor to the login", async () => {
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING" }));
    renderWithIntl(<StaffHome />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  });

  it("tells the person when the Auth service cannot be reached, without leaving the page", async () => {
    api.getSession.mockRejectedValue(new ApiError(0));
    renderWithIntl(<StaffHome />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Oturum bilgisi şu an alınamıyor");
    expect(replace).not.toHaveBeenCalled();
  });

  it("signs out and goes to the login", async () => {
    api.getSession.mockResolvedValue(session());
    renderWithIntl(<StaffHome />);

    fireEvent.click(await screen.findByRole("button", { name: "Çıkış yap" }));

    expect(api.logout).toHaveBeenCalledOnce();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  });

  it("still goes to the login when the sign-out call fails", async () => {
    api.getSession.mockResolvedValue(session());
    api.logout.mockRejectedValue(new ApiError(0));
    renderWithIntl(<StaffHome />);

    fireEvent.click(await screen.findByRole("button", { name: "Çıkış yap" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  });
});
