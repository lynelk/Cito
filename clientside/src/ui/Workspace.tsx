import React from 'react';
import { Card } from './Card';
import { ChevronRightIcon } from './Icons';

export type WorkspaceMetricTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

export function WorkspaceMetricGrid({ children }: { children: React.ReactNode }): React.ReactElement {
  return <section className="cito-metric-grid" aria-label="Key metrics">{children}</section>;
}

export function WorkspaceMetric({
  label,
  value,
  meta,
  tone = 'neutral',
}: {
  label: React.ReactNode;
  value: React.ReactNode;
  meta?: React.ReactNode;
  tone?: WorkspaceMetricTone;
}): React.ReactElement {
  return (
    <Card className={`cito-metric-card cito-metric-card--${tone}`}>
      <span className="cito-metric-card__label">{label}</span>
      <strong className="cito-metric-card__value">{value}</strong>
      {meta ? <span className="cito-metric-card__meta">{meta}</span> : null}
    </Card>
  );
}

export function WorkspaceGrid({ children }: { children: React.ReactNode }): React.ReactElement {
  return <section className="cito-workspace-grid">{children}</section>;
}

export function WorkspacePanel({
  eyebrow,
  title,
  actions,
  children,
  className = '',
}: {
  eyebrow?: React.ReactNode;
  title: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}): React.ReactElement {
  return (
    <Card className={`cito-workspace-panel ${className}`.trim()}>
      <header className="cito-workspace-panel__header">
        <div>
          {eyebrow ? <span className="cito-workspace-panel__eyebrow">{eyebrow}</span> : null}
          <h2>{title}</h2>
        </div>
        {actions ? <div className="cito-workspace-panel__actions">{actions}</div> : null}
      </header>
      <div className="cito-workspace-panel__body">{children}</div>
    </Card>
  );
}

export interface WorkspaceQuickAction {
  label: string;
  description?: string;
  onClick: () => void;
}

export function WorkspaceQuickActions({ actions }: { actions: WorkspaceQuickAction[] }): React.ReactElement {
  return (
    <div className="cito-quick-actions">
      {actions.map((action) => (
        <button type="button" className="cito-quick-action" key={action.label} onClick={action.onClick}>
          <span>
            <strong>{action.label}</strong>
            {action.description ? <small>{action.description}</small> : null}
          </span>
          <ChevronRightIcon size={18} />
        </button>
      ))}
    </div>
  );
}

export function WorkspaceStatusList({
  items,
}: {
  items: Array<{ label: string; value: React.ReactNode; tone?: WorkspaceMetricTone }>;
}): React.ReactElement {
  return (
    <div className="cito-status-list">
      {items.map((item) => (
        <div className="cito-status-list__row" key={item.label}>
          <span>{item.label}</span>
          <strong className={`cito-status-list__value cito-status-list__value--${item.tone || 'neutral'}`}>{item.value}</strong>
        </div>
      ))}
    </div>
  );
}

export function WorkspaceDisclosure({
  summary,
  children,
  defaultOpen = false,
}: {
  summary: React.ReactNode;
  children: React.ReactNode;
  defaultOpen?: boolean;
}): React.ReactElement {
  return (
    <details className="cito-disclosure" open={defaultOpen}>
      <summary>{summary}</summary>
      <div className="cito-disclosure__body">{children}</div>
    </details>
  );
}
