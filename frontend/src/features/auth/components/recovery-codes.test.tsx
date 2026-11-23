import { fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";
import { RecoveryCodes } from "./recovery-codes";

const codes = ["aaaa-bbbb-cccc-dddd-eeee", "ffff-gggg-hhhh-iiii-jjjj"];

afterEach(() => vi.unstubAllGlobals());

describe("recovery codes", () => {
  it("lists every code", () => {
    renderWithIntl(<RecoveryCodes codes={codes} onDone={vi.fn()} />);
    expect(screen.getAllByRole("listitem").map((item) => item.textContent)).toEqual(codes);
  });

  it("copies the codes one per line and confirms it", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    renderWithIntl(<RecoveryCodes codes={codes} onDone={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Kodları kopyala" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Kopyalandı" })).toBeInTheDocument());
    expect(writeText).toHaveBeenCalledWith(codes.join("\n"));
  });

  it("does not claim success when the clipboard is refused", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("denied"));
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    renderWithIntl(<RecoveryCodes codes={codes} onDone={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: "Kodları kopyala" }));

    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(screen.getByRole("button", { name: "Kodları kopyala" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Kopyalandı" })).not.toBeInTheDocument();
  });

  it("continues only through the done button", () => {
    const onDone = vi.fn();
    renderWithIntl(<RecoveryCodes codes={codes} onDone={onDone} />);
    expect(onDone).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Kaydettim, devam et" }));
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
