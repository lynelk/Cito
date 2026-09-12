import React from 'react';
import { NavGroup, NavItem, Icons } from '../ui';

const navGroups = [
  { title: 'Overview', items: [
    { value: 'insights', text: 'Dashboard', Icon: Icons.DashboardIcon },
  ] },
  { title: 'Services', items: [
    { value: 'money-operations', text: 'Payments', Icon: Icons.PaymentsIcon },
    { value: 'communicationrouting', text: 'Communications', Icon: Icons.SmsIcon },
    { value: 'vending', text: 'Vending & Utilities', Icon: Icons.LightningIcon },
    { value: 'risk-compliance', text: 'KYC & Identity', Icon: Icons.ShieldIcon },
    { value: 'platform', text: 'Billing & BaaS', Icon: Icons.ReceiptIcon },
  ] },
  { title: 'Business', items: [
    { value: 'merchants-accounts', text: 'Merchants / Businesses', Icon: Icons.StoreIcon },
    { value: 'merchant-readiness', text: 'Merchant readiness', Icon: Icons.ShieldIcon },
    { value: 'treasury', text: 'Treasury / Float', Icon: Icons.CardsIcon },
  ] },
  { title: 'Operations', items: [
    { value: 'airtel-money', text: 'Airtel Money', Icon: Icons.PaymentsIcon },
    { value: 'reconciliation', text: 'Reconciliation', Icon: Icons.ReconcileIcon },
  ] },
  { title: 'Platform', items: [
    { value: 'providers-integrations', text: 'Integrations / API', Icon: Icons.LightningIcon },
    { value: 'administration', text: 'Administration', Icon: Icons.UsersIcon },
    { value: 'api-reference', text: 'API workbench', Icon: Icons.CardsIcon },
    { value: 'engineering', text: 'Engineering / Internal', Icon: Icons.SettingsIcon },
  ] },
  { title: 'Account', items: [
    { value: 'settings', text: 'Settings', Icon: Icons.SettingsIcon },
  ] },
];

const canonicalRoutes = {
  'merchant-readiness': '/bo/merchant-readiness',
  'airtel-money': '/bo/airtel-money',
};

/** Canonical admin information architecture. Selection is route-driven by the host shell. */
export default function MainMenu({ activeItem, onChangeMenu }) {
  const select = (value) => {
    const route = canonicalRoutes[value];
    if (route) {
      window.location.assign(route);
      return;
    }
    onChangeMenu(value);
  };

  return (
    <>
      {navGroups.map((group) => (
        <NavGroup title={group.title} key={group.title}>
          {group.items.map((item) => (
            <NavItem key={item.value} icon={<item.Icon size={20} />} active={activeItem === item.value} onClick={() => select(item.value)}>
              {item.text}
            </NavItem>
          ))}
        </NavGroup>
      ))}
      <NavGroup bottom>
        <NavItem danger icon={<Icons.LogoutIcon size={20} />} onClick={() => onChangeMenu('exit')}>Logout</NavItem>
      </NavGroup>
    </>
  );
}