import React from 'react';
import { NavGroup, NavItem, Icons } from '../ui';

const navGroups = [
  { title: 'Overview', items: [
    { value: 'home', text: 'Dashboard', Icon: Icons.DashboardIcon },
  ] },
  { title: 'Services', items: [
    { value: 'payments', text: 'Payments', Icon: Icons.PaymentsIcon, service: 'CPAY' },
    { value: 'sms', text: 'Communications', Icon: Icons.SmsIcon },
    { value: 'services', text: 'Services & Products', Icon: Icons.LightningIcon },
  ] },
  { title: 'Business', items: [
    { value: 'balances-settlements', text: 'Balances & Settlements', Icon: Icons.ReconcileIcon, service: 'CPAY' },
    { value: 'customers', text: 'Customers', Icon: Icons.UsersIcon },
    { value: 'reports', text: 'Reports', Icon: Icons.ReceiptIcon },
  ] },
  { title: 'Platform', items: [
    { value: 'developers', text: 'Developers', Icon: Icons.CardsIcon },
    { value: 'business', text: 'Business', Icon: Icons.StoreIcon },
  ] },
  { title: 'Account', items: [
    { value: 'help', text: 'Help & Support', Icon: Icons.SmsIcon },
    { value: 'settings', text: 'Settings', Icon: Icons.SettingsIcon },
  ] },
];

/** Merchant navigation hides service-specific entries when an explicit entitlement set denies them. */
export default function MainMenuMerchant({ activeItem, onChangeMenu, entitlements }) {
  const known = Array.isArray(entitlements);
  const enabled = new Set((entitlements || []).map((value) => String(value).toUpperCase()));
  return (
    <>
      {navGroups.map((group) => (
        <NavGroup title={group.title} key={group.title}>
          {group.items.filter((item) => !item.service || !known || enabled.has(item.service)).map((item) => (
            <NavItem key={item.value} icon={<item.Icon size={20} />} active={activeItem === item.value} onClick={() => onChangeMenu(item.value)}>
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
