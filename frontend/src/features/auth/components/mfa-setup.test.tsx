import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { MfaSetup } from "./mfa-setup";

const enrollment = {
  secret: "jbswy3dpehpk3pxp",
  otpauthUri: "otpauth://totp/Restofy:admin?secret=JBSWY3DPEHPK3PXP&issuer=Restofy",
  expiresAt: "2030-01-01T00:10:00Z",
};

function setup(props: Partial<React.ComponentProps<typeof MfaSetup>> = {}) {
  const handlers = { onConfirm: vi.fn(), onBack: vi.fn() };
  renderWithIntl(<MfaSetup enrollment={enrollment} {...handlers} pending={false} {...props} />);
  return handlers;
}

describe("mfa setup", () => {
  it("shows the secret in groups of four to type by hand", () => {
    setup();
    expect(screen.getByTestId("mfa-secret")).toHaveTextContent("jbsw y3dp ehpk 3pxp");
  });

  it("shows a QR code and an open-in-app link for an otpauth address", () => {
    setup();
    expect(screen.getByRole("img", { name: "Doğrulama uygulaması için QR kodu" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Uygulamada aç" })).toHaveAttribute("href", enrollment.otpauthUri);
  });

  it("shows neither QR code nor link for an address that is not otpauth", () => {
    setup({ enrollment: { ...enrollment, otpauthUri: "javascript:alert(1)" } });
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByTestId("mfa-secret")).toBeInTheDocument();
  });

  it("explains the three steps", () => {
    setup();
    expect(screen.getByText(/doğrulama uygulaması \(Google Authenticator/)).toBeInTheDocument();
    expect(screen.getByText(/QR kodu tara/)).toBeInTheDocument();
    expect(screen.getByText("Uygulamanın gösterdiği 6 haneli kodu yaz.")).toBeInTheDocument();
  });

  it("does not confirm an empty or malformed code", () => {
    const { onConfirm } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Doğrula ve etkinleştir" }));
    expect(screen.getByText("Kodu yaz.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Doğrulama kodu"), { target: { value: "12ab56" } });
    expect(screen.queryByText("Kodu yaz.")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Doğrula ve etkinleştir" }));
    expect(screen.getByText("Kod biçimi geçersiz.")).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("confirms with the trimmed code", async () => {
    const { onConfirm } = setup();
    fireEvent.change(screen.getByLabelText("Doğrulama kodu"), { target: { value: " 654321 " } });
    fireEvent.click(screen.getByRole("button", { name: "Doğrula ve etkinleştir" }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith("654321"));
  });

  it("goes back through onBack", () => {
    const { onBack } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Geri dön" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("disables the button while pending and shows the server's problem", () => {
    setup({ pending: true, problem: "Kod hatalı ya da süresi dolmuş." });
    expect(screen.getByRole("button", { name: "Doğrula ve etkinleştir" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Kod hatalı ya da süresi dolmuş.");
  });
});
