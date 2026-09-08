import React from 'react';
import Logo from '../media/images/cito-mark.svg';
import * as Icons from './Icons';

export type AuthAsideIcon =
  | 'payments'
  | 'cards'
  | 'message'
  | 'verification'
  | 'insights'
  | 'support'
  | 'secure'
  | 'fast'
  | 'reliable'
  | 'users'
  | 'merchant'
  | 'settings';

export interface AuthAsideCard {
  id: string;
  icon: AuthAsideIcon;
  title: string;
  eyebrow?: string;
  detail?: string;
  tone?: 'neutral' | 'success';
}

export interface AuthAsideBenefit {
  icon: AuthAsideIcon;
  title: string;
  copy: string;
}

interface AuthLayoutProps {
  title: string;
  subtitle?: string;
  asideTitle?: string;
  asideCopy?: string;
  asideVariant?: 'brand' | 'media';
  asideImageUrl?: string;
  asideImageAlt?: string;
  asideCards?: AuthAsideCard[];
  asideBenefits?: AuthAsideBenefit[];
  footer?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}

const iconMap: Record<AuthAsideIcon, React.ComponentType<Icons.IconProps>> = {
  payments: Icons.CardsIcon,
  cards: Icons.CardsIcon,
  message: Icons.SmsIcon,
  verification: Icons.ShieldIcon,
  insights: Icons.BarChartIcon,
  support: Icons.HeadsetIcon,
  secure: Icons.LockIcon,
  fast: Icons.LightningIcon,
  reliable: Icons.ShieldIcon,
  users: Icons.UsersIcon,
  merchant: Icons.StoreIcon,
  settings: Icons.SettingsIcon,
};

function mediaBackground(imageUrl?: string): React.CSSProperties | undefined {
  const trimmedUrl = imageUrl?.trim();
  if (!trimmedUrl) return undefined;
  return {
    backgroundImage: `linear-gradient(180deg, rgba(8, 26, 42, 0.08), rgba(8, 26, 42, 0.14)), url("${trimmedUrl.replace(/"/g, '\\"')}")`,
  };
}

function AsideIcon({ icon, size = 26 }: { icon: AuthAsideIcon; size?: number }): React.ReactElement {
  const Icon = iconMap[icon] ?? Icons.CardsIcon;
  return <Icon size={size} />;
}

export function AuthLayout({
  title,
  subtitle,
  asideTitle = 'Cito',
  asideCopy,
  asideVariant = 'brand',
  asideImageUrl,
  asideImageAlt = 'Cito business services workspace',
  asideCards = [],
  asideBenefits = [],
  footer,
  className = '',
  children,
}: AuthLayoutProps): React.ReactElement {
  const mediaAside = asideVariant === 'media';

  return (
    <div className={`ios-auth ${className}`.trim()}>
      <main className="ios-auth__card" role="main">
        <aside
          className={`ios-auth__aside ${mediaAside ? 'ios-auth__aside--media' : ''}`.trim()}
          aria-label={mediaAside ? `${asideTitle}. ${asideCopy || asideImageAlt}` : 'Cito access context'}
          style={mediaAside ? mediaBackground(asideImageUrl) : undefined}
        >
          {mediaAside ? (
            <>
              <div className="ios-auth__media-orbit" aria-hidden="true">
                <span className="ios-auth__media-dot ios-auth__media-dot--one" />
                <span className="ios-auth__media-dot ios-auth__media-dot--two" />
                <span className="ios-auth__media-dot ios-auth__media-dot--three" />
              </div>
              {asideCards.map((card) => (
                <div
                  className={`ios-auth__media-card ios-auth__media-card--${card.id} ${card.tone === 'success' ? 'ios-auth__media-card--success' : ''}`.trim()}
                  key={card.id}
                >
                  <span className="ios-auth__media-card-icon"><AsideIcon icon={card.icon} /></span>
                  <span className="ios-auth__media-card-copy">
                    <strong>{card.title}</strong>
                    {card.eyebrow ? <span className="ios-auth__media-card-eyebrow">{card.eyebrow}</span> : null}
                    {card.detail ? <span>{card.detail}</span> : null}
                  </span>
                </div>
              ))}
              {asideBenefits.length > 0 ? (
                <div className="ios-auth__benefit-strip">
                  {asideBenefits.map((benefit) => (
                    <div className="ios-auth__benefit" key={benefit.title}>
                      <span className="ios-auth__benefit-icon"><AsideIcon icon={benefit.icon} size={24} /></span>
                      <strong>{benefit.title}</strong>
                      <span>{benefit.copy}</span>
                    </div>
                  ))}
                </div>
              ) : null}
            </>
          ) : (
            <>
              <img src={Logo} alt="Cito" />
              <h2>{asideTitle}</h2>
              {asideCopy ? <p>{asideCopy}</p> : null}
            </>
          )}
        </aside>
        <section className="ios-auth__main">
          <header className="ios-auth__header">
            <img src={Logo} alt="Cito" />
            <h1 className="ios-auth__title">{title}</h1>
            {subtitle ? <p className="ios-auth__subtitle">{subtitle}</p> : null}
          </header>
          <div className="ios-auth__content">{children}</div>
          {footer ? <footer className="ios-auth__footer">{footer}</footer> : null}
        </section>
      </main>
    </div>
  );
}
