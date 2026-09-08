import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Navigate, Routes, Route } from 'react-router-dom';
import AdminSessionGate from './components/AdminSessionGate';

// Public and authenticated surfaces are code-split so each entry point stays focused and light.
const CitoLandingPage = lazy(() => import('./components/CitoLandingPage'));
const CitoSignupGateway = lazy(() => import('./components/CitoSignupGateway'));
const VerifyEmail = lazy(() => import('./components/VerifyEmail'));
const PlatformLogin = lazy(() => import('./components/Login'));
const PartnerLogin = lazy(() => import('./components/LoginMerchant'));
const Layout = lazy(() => import('./components/Layout'));
const LayoutMerchant = lazy(() => import('./components/LayoutMerchant'));
const OperationsConsole = lazy(() => import('./features/OperationsConsole'));
const ProviderTreasuryConsole = lazy(() => import('./features/ProviderTreasuryConsole'));
const ProductionMaturityDashboard = lazy(() => import('./features/productionMaturity/ProductionMaturityDashboard'));
const PublicProductPage = lazy(() => import('./components/PublicExperiencePages').then((module) => ({ default: module.PublicProductPage })));
const PublicStatusPage = lazy(() => import('./components/PublicExperiencePages').then((module) => ({ default: module.PublicStatusPage })));
const PublicContactPage = lazy(() => import('./components/PublicExperiencePages').then((module) => ({ default: module.PublicContactPage })));

function RouteFallback(): React.ReactElement {
  return <div style={{ padding: 24 }}>Loading…</div>;
}

function protectAdmin(element: React.ReactElement): React.ReactElement {
  return <AdminSessionGate>{element}</AdminSessionGate>;
}

function Routers(): React.ReactElement {
  return (
    <BrowserRouter>
      <Suspense fallback={<RouteFallback />}>
        <Routes>
          <Route path="/" element={<CitoLandingPage />} />
          <Route path="/payments" element={<PublicProductPage page="payments" />} />
          <Route path="/payouts" element={<PublicProductPage page="payouts" />} />
          <Route path="/billing" element={<PublicProductPage page="billing" />} />
          <Route path="/operations-platform" element={<PublicProductPage page="operations" />} />
          <Route path="/developer-platform" element={<PublicProductPage page="developer-platform" />} />
          <Route path="/about" element={<PublicProductPage page="about" />} />
          <Route path="/security" element={<PublicProductPage page="security" />} />
          <Route path="/status" element={<PublicStatusPage />} />
          <Route path="/contact" element={<PublicContactPage />} />
          <Route path="/signup" element={<CitoSignupGateway />} />
          <Route path="/verify-email" element={<VerifyEmail />} />

          {/* Canonical portal roots: BO = platform administration, FO = merchant/partner operations. */}
          <Route path="/bo" element={<PlatformLogin />} />
          <Route path="/bo/operations" element={protectAdmin(<OperationsConsole />)} />
          <Route path="/bo/provider-treasury" element={protectAdmin(<ProviderTreasuryConsole />)} />
          <Route path="/bo/production-maturity" element={protectAdmin(<ProductionMaturityDashboard />)} />
          <Route path="/bo/*" element={protectAdmin(<Layout />)} />

          <Route path="/fo" element={<PartnerLogin />} />
          <Route path="/fo/*" element={<LayoutMerchant />} />

          {/* Backward-compatible aliases. Keep the legacy BO workspace mounted while its
              internal menu routes are migrated incrementally to the canonical /bo/* paths. */}
          <Route path="/bo/admin" element={<Navigate to="/bo" replace />} />
          <Route path="/bo/admin/operations" element={<Navigate to="/bo/operations" replace />} />
          <Route path="/bo/admin/provider-treasury" element={<Navigate to="/bo/provider-treasury" replace />} />
          <Route path="/bo/admin/production-maturity" element={<Navigate to="/bo/production-maturity" replace />} />
          <Route path="/bo/admin/*" element={protectAdmin(<Layout />)} />
          <Route path="/bo/partner" element={<Navigate to="/fo" replace />} />
          <Route path="/bo/partner/*" element={<Navigate to="/fo/dashboard" replace />} />

          <Route path="/login" element={<Navigate to="/bo" replace />} />
          <Route path="/portal" element={<Navigate to="/bo" replace />} />
          <Route path="/admin" element={<Navigate to="/bo" replace />} />
          <Route path="/admin/operations" element={<Navigate to="/bo/operations" replace />} />
          <Route path="/admin/provider-treasury" element={<Navigate to="/bo/provider-treasury" replace />} />
          <Route path="/admin/production-maturity" element={<Navigate to="/bo/production-maturity" replace />} />
          <Route path="/admin/*" element={<Navigate to="/bo/insights" replace />} />
          <Route path="/partner" element={<Navigate to="/fo" replace />} />
          <Route path="/partner/*" element={<Navigate to="/fo/dashboard" replace />} />
          <Route path="/dashboard/*" element={<Navigate to="/bo/insights" replace />} />
          <Route path="/dashboardMerchant/*" element={<Navigate to="/fo/dashboard" replace />} />
          <Route path="/operations" element={<Navigate to="/bo/operations" replace />} />
          <Route path="/provider-treasury" element={<Navigate to="/bo/provider-treasury" replace />} />
          <Route path="/production-maturity" element={<Navigate to="/bo/production-maturity" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}

export default Routers;
