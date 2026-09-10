import React from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CitoLandingPage from './CitoLandingPage';

function renderLandingPage() {
  return render(
    <MemoryRouter>
      <CitoLandingPage />
    </MemoryRouter>,
  );
}

describe('CitoLandingPage', () => {
  it('positions Cito as a multi-service business platform', () => {
    renderLandingPage();

    expect(screen.getByRole('heading', { level: 1, name: /one platform for the services your business runs on/i })).toBeInTheDocument();
    expect(screen.getByText(/accept payments, make payouts, communicate with customers/i)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /business infrastructure without the usual integration sprawl/i })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { name: /identity, credit & scoring/i }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('heading', { name: /communications/i }).length).toBeGreaterThan(0);
  });

  it('routes every primary access action to the live Cito access screens', () => {
    renderLandingPage();

    const signInLinks = screen.getAllByRole('link', { name: /sign in/i });
    const signUpLinks = screen.getAllByRole('link', { name: /create cito account|get started|create account/i });

    signInLinks.forEach((link) => expect(link).toHaveAttribute('href', '/login'));
    signUpLinks.forEach((link) => expect(link).toHaveAttribute('href', '/signup'));
  });

  it('surfaces the full service portfolio without implying provider certification', () => {
    renderLandingPage();

    expect(screen.getByText(/sms, whatsapp business and ussd/i)).toBeInTheDocument();
    expect(screen.getByText(/nin, kyc\/kyb, crb reports/i)).toBeInTheDocument();
    expect(screen.getByText(/metering, rating, invoicing and billing-as-a-service/i)).toBeInTheDocument();
    expect(screen.getByText(/airtime, data, utilities, devices/i)).toBeInTheDocument();
    expect(screen.getByText(/production availability is explicit per provider, country and account/i)).toBeInTheDocument();
  });

  it('exposes Cito Payments developer documentation and provider-family context', () => {
    renderLandingPage();

    expect(screen.getAllByRole('link', { name: /cito payments api documentation/i })[0]).toHaveAttribute(
      'href',
      '/fo/developers',
    );
    expect(screen.getByText(/MTN MoMo · Airtel Money · Yo! Payments · Safaricom M-Pesa · FlexiPay/i)).toBeInTheDocument();
  });

  it('includes direct Cito sales and support contact paths', () => {
    renderLandingPage();

    expect(screen.getByRole('link', { name: /discuss Cito services for your business/i })).toHaveAttribute('href', '/contact');
    expect(screen.getByRole('link', { name: /get help with an existing Cito account/i })).toHaveAttribute(
      'href',
      'mailto:support@citotech.net',
    );
  });

  it('shows Core-Synergies as the copyright owner', () => {
    renderLandingPage();
    expect(screen.getByText(/© .* Core-Synergies/i)).toBeInTheDocument();
  });
});
