import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, fireEvent } from '@testing-library/react';
import ModuleSettings from './ModuleSettings';

vi.mock('../../shared/api/httpClient', () => ({ apiFetch: vi.fn(async () => ({ text: async () => JSON.stringify({ code: '000', data: [
  { name: 'gw_mtn_api_url', label: 'Legacy MTN URL', setting_group: 'MTN', setting_value: 'https://developer.invalid' },
  { name: 'gw_mtn_api_customer_charge_inbound', label: 'Inbound charge', setting_group: 'MTN', setting_value: '1' },
] }) })) }));

describe('settings readiness and canonical MTN entry point', () => {
  it('never claims connectivity from stored settings or displays invented request history', () => {
    const source = fs.readFileSync(path.resolve('src/components/modules/ModuleSettings.jsx'), 'utf8');
    expect(source).not.toContain('09:31 AM');
    expect(source).not.toContain('None in last 24h');
    expect(source).not.toContain('testConnection(section)');
    expect(source).not.toContain(' Connected');
    expect(source).toContain('isManagedMtnConnectionSetting');
    expect(source).toContain('this.dirtyRows().filter');
  });
  it('directs MTN configuration to the governed form and retains separate charge controls', async () => {
    render(<MemoryRouter><ModuleSettings loader={() => {}} /></MemoryRouter>);
    fireEvent.click(await screen.findByText('MTN MoMo'));
    expect(await screen.findByRole('link', { name: 'Configure and verify MTN MoMo' })).toHaveAttribute('href', '/bo/admin/mtn-momo?environment=PRODUCTION');
    expect(screen.queryByLabelText('Legacy MTN URL')).toBeNull();
    expect(await screen.findByLabelText('Inbound charge')).toBeTruthy();
  });
});
