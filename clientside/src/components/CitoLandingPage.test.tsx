import React from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import CitoLandingPage from "./CitoLandingPage";
import { PublicProductPage } from "./PublicExperiencePages";
import { PublicHeader } from "./PublicSiteChrome";

function renderLandingPage() {
  return render(
    <MemoryRouter>
      <CitoLandingPage />
    </MemoryRouter>,
  );
}

describe("Cito public website", () => {
  it("connects all six service-directory links to real sections", () => {
    renderLandingPage();
    expect(
      screen.getByRole("heading", {
        level: 1,
        name: /your business.*better connected/i,
      }),
    ).toBeInTheDocument();
    const directory = screen.getByRole("complementary", {
      name: /explore cito services/i,
    });
    const links = within(directory).getAllByRole("link");
    expect(links).toHaveLength(6);
    links.forEach((link) =>
      expect(
        document.querySelector(link.getAttribute("href")!),
      ).toBeInTheDocument(),
    );
  });
  it("keeps registration, sign-in, support and API documentation on their existing routes", () => {
    renderLandingPage();
    screen
      .getAllByRole("link", { name: /sign in/i })
      .forEach((link) => expect(link).toHaveAttribute("href", "/login"));
    screen
      .getAllByRole("link", { name: /create cito account|get started/i })
      .forEach((link) => expect(link).toHaveAttribute("href", "/signup"));
    expect(
      screen.getByRole("link", { name: /cito payments api documentation/i }),
    ).toHaveAttribute("href", "/fo/developers");
    expect(
      screen.getByRole("link", {
        name: /get help with an existing cito account/i,
      }),
    ).toHaveAttribute("href", "mailto:support@citotech.net");
  });
  it("preserves provider-readiness guidance and searchable public API topics", () => {
    renderLandingPage();
    expect(
      screen.getByText(
        /production availability is explicit per provider, country and account/i,
      ),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByText("Explore API topics"));
    fireEvent.change(screen.getByRole("searchbox"), {
      target: { value: "asynchronous" },
    });
    expect(
      screen.getByText(/asynchronous callbacks are not supported/i),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByRole("searchbox"), {
      target: { value: "no-such-topic-xyz" },
    });
    expect(screen.getByText(/no matching topics/i)).toBeInTheDocument();
  });
  it("uses the shared accessible navigation and footer on product pages", () => {
    render(
      <MemoryRouter>
        <PublicProductPage page="billing" />
      </MemoryRouter>,
    );
    expect(
      screen.getByRole("link", { name: /skip to content/i }),
    ).toHaveAttribute("href", "#main-content");
    expect(document.getElementById("main-content")).toBeInTheDocument();
    expect(screen.getByText(/© .*Core-Synergies/i)).toBeInTheDocument();
    expect(
      screen.getAllByRole("link", { name: /sign in/i })[0],
    ).toHaveAttribute("href", "/login");
  });
  it("closes the mobile menu after navigation and on Escape", () => {
    render(
      <MemoryRouter>
        <PublicHeader />
        <Routes>
          <Route path="*" element={<div />} />
        </Routes>
      </MemoryRouter>,
    );
    const summary = screen.getByText("Menu");
    const details = summary.closest("details")!;
    details.open = true;
    fireEvent.keyDown(summary, { key: "Escape" });
    expect(details.open).toBe(false);
    expect(summary).toHaveFocus();
    details.open = true;
    fireEvent.click(
      within(
        screen.getByRole("navigation", { name: "Mobile navigation" }),
      ).getByRole("link", { name: "Billing" }),
    );
    expect(details.open).toBe(false);
  });
});
