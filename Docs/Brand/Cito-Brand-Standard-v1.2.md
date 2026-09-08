# Cito brand development standard

Version 1.2 · Active implementation baseline · 8 September 2026

## Foundation
Cito is the platform brand. Cito Payments (CPay) remains the payment service/channel name where technically or contractually required. The product should feel clear, modern, confident and practical rather than decorative. The reference dashboard supplied for this rollout may inform hierarchy, card density, whitespace, compact navigation and information architecture; its header artwork is not part of the Cito system and must not be copied.

## Approved identity
Use the supplied Cito circuit-C mark as the product mark. The approved product assets are the colour mark, monochrome mark and the derived rounded-square favicon/app icon created from the supplied mark. Do not distort, skew, rotate or redraw the mark. Keep generous clear space and maintain proportions.

The browser favicon and installed-app icons use the supplied mark in white on a Cito-blue rounded square. The monochrome theme uses the supplied black monochrome mark rather than a CSS recolouring filter.

## Colour
Primary Cito Blue #0066FF.
Deep Blue #0047B3.
Sky Blue #2EA3FF.
Ice Blue #E6F2FF.
Navy #0B2545.
Slate Grey #94A3B8.

Supporting application colours remain semantic and evidence-driven. Success #147D57, warning #8A5700, error #B42318 and information #0047B3. Operational status must always include text; colour is never the only signal.

## Typography
Headings use Space Grotesk when available, with Inter and system sans-serif fallbacks. Body/UI uses Inter with system sans-serif/Arial fallbacks. No font files are distributed with the application. Use a 4 px spacing basis, 8 px control radius and 12 px card radius.

## Product themes
Cito is the default product theme. Users may switch to the approved Monochrome presentation. Brand mode is separate from light/dark/system appearance preference and is persisted locally per browser.

Monochrome changes the product chrome, navigation accents, cards and logo presentation to neutral tones. Semantic operational status colours may remain visible because risk and transaction state clarity outrank decorative purity.

## Interface direction
Use clean white or ice-tinted surfaces, strong Navy hierarchy, concise blue actions, restrained shadows, compact cards and readable data density. Prefer useful whitespace over oversized empty panels. Empty states must stay compact and preserve their panel headings. Do not fabricate production activity to make a dashboard look populated.

The reference interface supplied with this release is inspiration for compact navigation, card composition and analytical hierarchy only. Ignore its decorative header image.

## Architecture and claims
Use descriptive service groups: Payments, Communications, Identity & Risk, Vending, Billing & Finance, Integrations & Automation. CPay and provider names live inside the relevant service context. Code presence is not provider certification. Sandbox and production remain visibly and technically distinct.

Branding never changes financial precision, tenant isolation, authentication, CSRF/signing, maker-checker, idempotency, audit, reconciliation or provider activation rules.

## Accessibility and responsive acceptance
Target WCAG 2.2 AA. Prefer 44 × 44 CSS-pixel controls. Normal text must meet 4.5:1 contrast and meaningful controls/graphics 3:1. Test changed surfaces at 320, 390, 768 and 1440 CSS pixels, with keyboard use, 200% text, reduced motion and empty/error/loading states.

## Definition of done
A brand-facing release updates the current pointer, tokens, product assets, favicon/PWA icons, tests and relevant product surfaces together. Record the current brand version in the PR. Do not claim deployment until the exact production release has been verified.
