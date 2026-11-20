import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { ApiError } from "@/shared/lib/api";

const replace = vi.hoisted(() => vi.fn());
const api = vi.hoisted(() => ({
  login: vi.fn(),
  getSession: vi.fn(),
  verifyTotp: vi.fn(),
  startMfaEnrollment: vi.fn(),
  confirmMfaEnrollment: vi.fn(),
  recoverWithCode: vi.fn(),
  logout: vi.fn(),
}));

vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("../api/auth-api", () => api);

import { LoginScreen } from "./login-screen";

const session = (overrides = {}) => ({
  status: "AUTHENTICATED",
  role: "ADMIN",
  idleExpiresAt: "2030-01-01T00:00:00Z",
  absoluteExpiresAt: "2030-01-01T08:00:00Z",
  ...overrides,
});

function signIn(email = "admin@restofy.com", password = "secret-password") {
  fireEvent.change(screen.getByLabelText("E-posta"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Parola"), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: "Giriş yap" }));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.logout.mockResolvedValue(undefined);
});

describe("login screen", () => {
  it("starts on the admin sign-in", () => {
    renderWithIntl(<LoginScreen />);
    expect(screen.getByRole("heading", { level: 1, name: "Yönetici girişi" })).toBeInTheDocument();
    expect(screen.getByRole("tabpanel")).toHaveAttribute("aria-labelledby", "login-role-ADMIN");
  });

  it("offers the brand and the theme switch", () => {
    renderWithIntl(<LoginScreen />);
    expect(screen.getAllByAltText("Restofy")).toHaveLength(2);
    expect(screen.getByRole("button", { name: /tema/i })).toBeInTheDocument();
  });

  it("switches the heading to the chosen role and slides forward", () => {
    renderWithIntl(<LoginScreen />);
    fireEvent.click(screen.getByRole("tab", { name: "Mutfak" }));
    const heading = screen.getByRole("heading", { level: 1, name: "Mutfak girişi" });
    expect(heading.closest(".login-slide")).toHaveAttribute("data-direction", "next");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("aria-labelledby", "login-role-KITCHEN");
  });

  it("slides backwards when moving to an earlier role", () => {
    renderWithIntl(<LoginScreen />);
    fireEvent.click(screen.getByRole("tab", { name: "Mutfak" }));
    fireEvent.click(screen.getByRole("tab", { name: "Garson" }));
    const heading = screen.getByRole("heading", { level: 1, name: "Garson girişi" });
    expect(heading.closest(".login-slide")).toHaveAttribute("data-direction", "prev");
  });

  it("does not animate when the same role is chosen again", () => {
    renderWithIntl(<LoginScreen />);
    fireEvent.click(screen.getByRole("tab", { name: "Yönetici" }));
    const heading = screen.getByRole("heading", { level: 1, name: "Yönetici girişi" });
    expect(heading.closest(".login-slide")).not.toHaveAttribute("data-direction");
  });

  it("keeps what was typed when the role changes", () => {
    renderWithIntl(<LoginScreen />);
    fireEvent.change(screen.getByLabelText("E-posta"), { target: { value: "garson@restofy.com" } });
    fireEvent.click(screen.getByRole("tab", { name: "Garson" }));
    expect(screen.getByLabelText("E-posta")).toHaveValue("garson@restofy.com");
  });

  it("offers the password reset to admins only", () => {
    renderWithIntl(<LoginScreen />);
    expect(screen.getByRole("link", { name: "Parolamı unuttum" })).toHaveAttribute("href", "/forgot-password");
    fireEvent.click(screen.getByRole("tab", { name: "Garson" }));
    expect(screen.queryByRole("link", { name: "Parolamı unuttum" })).not.toBeInTheDocument();
  });

  it("leaves for the application after a plain sign-in", async () => {
    api.login.mockResolvedValue(session());
    renderWithIntl(<LoginScreen />);
    signIn();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    expect(api.login).toHaveBeenCalledWith("admin@restofy.com", "secret-password");
  });

  it("tells the person when the credentials are wrong and drops the message on a role change", async () => {
    api.login.mockRejectedValue(new ApiError(401));
    renderWithIntl(<LoginScreen />);
    signIn();
    expect(await screen.findByRole("alert")).toHaveTextContent("E-posta veya parola hatalı.");
    fireEvent.click(screen.getByRole("tab", { name: "Garson" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("ends a session whose role is not the chosen one", async () => {
    api.login.mockResolvedValue(session({ role: "WAITER" }));
    renderWithIntl(<LoginScreen />);
    signIn();
    expect(await screen.findByRole("alert")).toHaveTextContent("Bu hesap seçtiğin rol için değil");
    expect(api.logout).toHaveBeenCalled();
    expect(replace).not.toHaveBeenCalled();
  });

  it("asks for the authenticator code when MFA is already set up", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: false }));
    renderWithIntl(<LoginScreen />);
    signIn();
    expect(await screen.findByRole("heading", { level: 1, name: "İki adımlı doğrulama" })).toBeInTheDocument();
    expect(screen.getByText("Doğrulama uygulamandaki 6 haneli kodu yaz.")).toBeInTheDocument();
    expect(api.startMfaEnrollment).not.toHaveBeenCalled();

    api.verifyTotp.mockResolvedValue(session());
    fireEvent.change(screen.getByLabelText("Doğrulama kodu"), { target: { value: "123456" } });
    fireEvent.click(screen.getByRole("button", { name: "Doğrula" }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    expect(api.verifyTotp).toHaveBeenCalledWith("123456");
  });

  it("walks a first-time admin through enrolment and the recovery codes", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: true }));
    api.startMfaEnrollment.mockResolvedValue({
      secret: "JBSWY3DPEHPK3PXP",
      otpauthUri: "otpauth://totp/Restofy:admin?secret=JBSWY3DPEHPK3PXP",
      expiresAt: "2030-01-01T00:10:00Z",
    });
    renderWithIntl(<LoginScreen />);
    signIn();
    expect(await screen.findByRole("heading", { level: 1, name: "Doğrulama uygulamasını kur" })).toBeInTheDocument();
    expect(screen.getByTestId("mfa-secret")).toHaveTextContent("JBSW Y3DP EHPK 3PXP");

    api.confirmMfaEnrollment.mockResolvedValue({
      session: session(),
      recoveryCodes: ["aaaa-bbbb-cccc-dddd-eeee"],
    });
    fireEvent.change(screen.getByLabelText("Doğrulama kodu"), { target: { value: "654321" } });
    fireEvent.click(screen.getByRole("button", { name: "Doğrula ve etkinleştir" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Kurtarma kodların" })).toBeInTheDocument();
    expect(screen.getByText("aaaa-bbbb-cccc-dddd-eeee")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Kaydettim, devam et" }));
    expect(replace).toHaveBeenCalledWith("/");
  });

  it("ends the half-finished session when the person goes back from the code step", async () => {
    api.login.mockResolvedValue(session({ status: "MFA_PENDING" }));
    api.getSession.mockResolvedValue(session({ status: "MFA_PENDING", mfaSetupRequired: false }));
    renderWithIntl(<LoginScreen />);
    signIn();
    await screen.findByRole("heading", { level: 1, name: "İki adımlı doğrulama" });
    fireEvent.click(screen.getByRole("button", { name: "Geri dön" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Yönetici girişi" })).toBeInTheDocument();
    expect(api.logout).toHaveBeenCalled();
  });
});
