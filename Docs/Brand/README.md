# Current Cito brand standard

Read `LATEST.json` before every design, writing or development task. It identifies the current standard and token release. Root `AGENTS.md` makes this part of contributor and agent instructions.

## Release 1.2
Effective 8 September 2026, release 1.2 supersedes the 1.1 implementation baseline for product-facing brand work. It adopts the supplied circuit-C colour mark and monochrome mark, the new Cito blue palette, Space Grotesk-style display stack with Inter/system fallbacks, the approved favicon/app icon treatment, and separate **Cito** and **Monochrome** product modes. Light/dark/system appearance remains an independent setting.

The reference dashboard supplied with this rollout may inform compact navigation, information hierarchy, card density and analytical composition. Its decorative header image is explicitly excluded.

## Required PR evidence
Every brand-facing PR records:

Brand version: 1.2

Brand impact: [changed touchpoints, or not applicable with a reason]

Evidence: [controlled assets/tokens, functional tests, responsive/state checks, accessibility evidence and runtime verification]

Release scope: [exact surfaces changed and remaining external/provider dependencies]

Run `python3 Docs/Brand/check_brand.py`. The automated check validates the engineering brand mirror and selected token/contrast rules; it does not replace visual, accessibility, legal or runtime review.

## Controlled product assets
- `clientside/src/media/images/cito-mark.svg` — colour circuit-C mark
- `clientside/src/media/images/cito-mark-mono.svg` — monochrome mark
- `clientside/public/favicon.svg` — rounded-square browser/PWA favicon derived from the supplied mark

Keep proportions intact. Do not skew, rotate or recreate the supplied mark. Semantic transaction, risk and system states retain text labels and may retain their semantic colours in monochrome mode.

## Synchronisation
Update `LATEST.json`, the standard, toolkit, token metadata, generated product assets, tests and change log together. Keep superseded standards for history, but do not use them as the current implementation source.
