import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RestofyLogo } from "./restofy-logo";

describe("Restofy logo", () => {
  it("renders a light and a dark variant that the theme switches between", () => {
    const { container } = render(<RestofyLogo className="w-40" />);
    const images = screen.getAllByAltText("Restofy");
    expect(images).toHaveLength(2);
    expect(container.querySelector(".theme-only-light")).toHaveAttribute(
      "src",
      "/brand/restofy-logo-light.svg",
    );
    expect(container.querySelector(".theme-only-dark")).toHaveAttribute(
      "src",
      "/brand/restofy-logo-dark.svg",
    );
    for (const image of images) expect(image).toHaveClass("w-40");
  });
});
