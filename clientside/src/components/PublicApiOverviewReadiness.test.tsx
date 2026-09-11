import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import PublicApiOverview from './PublicApiOverview';

afterEach(cleanup);

describe('public API readiness guidance', () => {
  it('makes the identity callback restriction searchable without credentials', () => {
    render(<PublicApiOverview />);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'asynchronous' } });
    expect(screen.getByText('Identity')).toBeTruthy();
    expect(screen.getByText(/Asynchronous callbacks are not supported/)).toBeTruthy();
    expect(screen.queryByText('Payments')).toBeNull();
  });

  it('distinguishes configuration, activation and final delivery evidence', () => {
    render(<PublicApiOverview />);
    expect(screen.getByText(/production enabled are different states/)).toBeTruthy();
    expect(screen.getByText(/do not prove final SMS or email delivery/)).toBeTruthy();
  });
});
