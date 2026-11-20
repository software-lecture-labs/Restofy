import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { LoginForm } from "./login-form";

function fill(email: string, password: string) {
  fireEvent.change(screen.getByLabelText("E-posta"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Parola"), { target: { value: password } });
}

describe("login form", () => {
  it("shows what is missing and does not submit", () => {
    const onSubmit = vi.fn();
    renderWithIntl(<LoginForm onSubmit={onSubmit} pending={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Giriş yap" }));
    expect(screen.getByText("E-posta adresini yaz.")).toBeInTheDocument();
    expect(screen.getByText("Parolanı yaz.")).toBeInTheDocument();
    expect(screen.getByLabelText("E-posta")).toHaveAttribute("aria-invalid", "true");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("rejects a malformed email", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} />);
    fill("garson", "secret");
    fireEvent.click(screen.getByRole("button", { name: "Giriş yap" }));
    expect(screen.getByText("Geçerli bir e-posta adresi yaz.")).toBeInTheDocument();
  });

  it("clears a field's error as soon as it is edited", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Giriş yap" }));
    fireEvent.change(screen.getByLabelText("E-posta"), { target: { value: "a" } });
    expect(screen.queryByText("E-posta adresini yaz.")).not.toBeInTheDocument();
    expect(screen.getByText("Parolanı yaz.")).toBeInTheDocument();
  });

  it("submits the trimmed email and the password exactly as typed", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    renderWithIntl(<LoginForm onSubmit={onSubmit} pending={false} />);
    fill("  garson@restofy.com ", "  secret ");
    fireEvent.click(screen.getByRole("button", { name: "Giriş yap" }));
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith({ email: "garson@restofy.com", password: "  secret " }),
    );
  });

  it("disables the button while the caller reports a request in flight", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending />);
    expect(screen.getByRole("button", { name: "Giriş yap" })).toBeDisabled();
  });

  it("enables the button when nothing is pending", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} />);
    expect(screen.getByRole("button", { name: "Giriş yap" })).toBeEnabled();
  });

  it("shows the server's problem as an alert", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} problem="E-posta veya parola hatalı." />);
    expect(screen.getByRole("alert")).toHaveTextContent("E-posta veya parola hatalı.");
  });

  it("shows no alert without a problem", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("toggles password visibility", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} />);
    const field = screen.getByLabelText("Parola");
    expect(field).toHaveAttribute("type", "password");
    fireEvent.click(screen.getByRole("button", { name: "Parolayı göster" }));
    expect(field).toHaveAttribute("type", "text");
    fireEvent.click(screen.getByRole("button", { name: "Parolayı gizle" }));
    expect(field).toHaveAttribute("type", "password");
  });

  it("limits the input to what the Auth service accepts and keeps browser hints", () => {
    renderWithIntl(<LoginForm onSubmit={vi.fn()} pending={false} />);
    expect(screen.getByLabelText("E-posta")).toHaveAttribute("maxlength", "300");
    expect(screen.getByLabelText("E-posta")).toHaveAttribute("autocomplete", "username");
    expect(screen.getByLabelText("Parola")).toHaveAttribute("maxlength", "72");
    expect(screen.getByLabelText("Parola")).toHaveAttribute("autocomplete", "current-password");
  });
});
