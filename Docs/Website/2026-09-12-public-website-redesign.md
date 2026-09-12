# Cito public website redesign

Brand version: 1.2
Brand impact: Homepage, shared public navigation/footer, product/company pages, contact and status page chrome. Approved colour and monochrome marks and canonical favicon are retained.

## Review and changes

The live public origin returned HTTP 200 and the current Cito metadata on 12 September 2026. The implementation review used main at ac9d200c8f6b3e2a91c241461593479d397ae31a. An authenticated operational audit and a browser visual audit were not performed in this task.

The homepage repeated lengthy governance descriptions across its hero, service, payment, about, access and closing sections. Its stylesheet also redefined the CSS-based logo treatment. Public subpages used a separate header, omitted the mobile menu and footer, and linked sign-in to `/bo` rather than the shared `/login` gateway.

This update:

- Leads with a short explanation of Cito and two existing business actions: account creation and sales contact.
- Connects six service-directory links to the corresponding content, retaining clear provider/account/country availability qualifications.
- Uses the supplied logo as an explicit image with an approved monochrome alternative; public pages share navigation, sign-in destination and footer.
- Gives payments, onboarding, developers and trust sections distinct layouts with fewer repeated paragraphs.
- Retains public API topic search, including provider-readiness and identity-callback restrictions, inside an expandable section.
- Adds a skip link, consistent main landmark, keyboard-visible focus, mobile navigation dismissal, 44px primary targets, responsive grids and reduced-motion rules.
- Corrects product-page heading hierarchy; contact form submission and status data remain on their existing backend contracts.

## Scope and synchronization

Frontend/backend parity: FRONTEND_ONLY
Parity rationale: Presentation and navigation maintenance. No new operational capability, endpoint, data model, permission, provider activation, pricing, money movement or callback behavior. The existing signup, sales, status and API-reference journeys are preserved.
Parity exception: None required for this presentation-only change; no one-sided operational capability change is introduced.

Merchant/admin portals, OpenAPI contracts, generated integration references and the backend require no changes because their capabilities, contracts and audience boundaries are unchanged. The standard, toolkit, latest pointer and token metadata remain at 1.2: this applies the active brand rather than releasing a new identity. Dependencies and lockfile are unchanged. Installation, backend configuration, migrations and deployment topology remain unchanged.

## Evidence

- Frontend suite: 55 test files, 306 tests passed.
- TypeScript validation and production build passed.
- `python3 Docs/Brand/check_brand.py` passed.
- Eight exported public pages have valid local links/assets and one H1 per page.
- Axe structural checks passed on those eight pages in JSDOM. Colour contrast is excluded from that DOM-only run because it needs rendered-browser evidence.
- Existing React and exported-page tests cover service anchors, account links, developer topic filtering/empty state, and mobile Escape dismissal.
- CSS uses layouts for compact mobile, tablet and desktop, semantic HTML and rem typography; rendered verification at 320, 390, 768 and 1440px, 200% text enlargement and screen-reader review remain release-review items. These are not claimed as completed by unit tests.

## Private review publication

`node clientside/scripts/export-public-review.mjs /absolute/empty/output` creates an eight-page presentation snapshot from these React components. Run it from an environment with the clientside dependencies installed. The export keeps account, contact, status and merchant-reference links on `https://cito.coresynergi.es`, and does not copy authentication, account data, live status or sales submission into static hosting. It includes a noindex directive and is published with owner-private access. The normal application build continues to use its existing routes and backend.

## Release and rollback

Submit the changes through `feature/* -> main -> sandbox -> production`, with repository review and CI. Verify the public homepage, product pages, menu, form states and account handoffs on the accepted staging release. Existing release controls require backend and frontend to use the same accepted SHA. Do not claim the original public domain has been updated from the private review publication.

Rollback: revert the website PR and release the accepted rollback SHA through the same path. No data migration or provider configuration rollback is needed. External provider certification remains independent.
