import React from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import logo from "../media/images/cito-mark.svg";
import monoLogo from "../media/images/cito-mark-mono.svg";

export function CitoWordmark(): React.ReactElement {
  return (
    <Link className="cito-brand" to="/" aria-label="Cito home">
      <img
        className="cito-logo-colour"
        src={logo}
        width="44"
        height="36"
        alt=""
      />
      <img
        className="cito-logo-mono"
        src={monoLogo}
        width="44"
        height="36"
        alt=""
      />
      <strong>
        Cito<span>Business services</span>
      </strong>
    </Link>
  );
}

export function PublicHeader(): React.ReactElement {
  const menu = React.useRef<HTMLDetailsElement>(null);
  const location = useLocation();
  React.useEffect(() => {
    if (menu.current) menu.current.open = false;
  }, [location]);
  const links = (
    <>
      <NavLink to="/payments">Payments</NavLink>
      <NavLink to="/billing">Billing</NavLink>
      <NavLink to="/developer-platform">Developers</NavLink>
      <NavLink to="/about">Company</NavLink>
    </>
  );
  return (
    <>
      <a className="cito-skip-link" href="#main-content">
        Skip to content
      </a>
      <header className="cito-header">
        <CitoWordmark />
        <nav className="cito-nav" aria-label="Primary navigation">
          {links}
        </nav>
        <div className="cito-header-actions">
          <Link className="cito-button cito-button-quiet" to="/login">
            Sign in
          </Link>
          <Link className="cito-button cito-button-primary" to="/signup">
            Get started <span aria-hidden="true">↗</span>
          </Link>
        </div>
        <details
          className="cito-mobile-menu"
          ref={menu}
          onKeyDown={(event) => {
            if (event.key === "Escape" && menu.current) {
              menu.current.open = false;
              menu.current.querySelector("summary")?.focus();
            }
          }}
        >
          <summary>Menu</summary>
          <nav className="cito-mobile-panel" aria-label="Mobile navigation">
            {links}
            <Link to="/status">Service status</Link>
            <Link to="/contact">Contact</Link>
            <Link to="/login">Sign in</Link>
            <Link to="/signup">Get started</Link>
          </nav>
        </details>
      </header>
    </>
  );
}

export function PublicFooter(): React.ReactElement {
  return (
    <footer className="cito-footer">
      <div className="cito-footer-top">
        <div className="cito-footer-brand">
          <CitoWordmark />
          <p>
            The services your business needs.
            <br />
            Connected through Cito.
          </p>
        </div>
        <div className="cito-footer-links">
          <div>
            <strong>Platform</strong>
            <Link to="/payments">Payments</Link>
            <Link to="/payouts">Payouts</Link>
            <Link to="/billing">Billing</Link>
            <Link to="/operations">Operations</Link>
          </div>
          <div>
            <strong>Resources</strong>
            <Link to="/developer-platform">Developers</Link>
            <a href="/fo/developers">API documentation</a>
            <Link to="/security">Security & trust</Link>
            <Link to="/status">Service status</Link>
          </div>
          <div>
            <strong>Company</strong>
            <Link to="/about">About Cito</Link>
            <Link to="/contact">Contact sales</Link>
            <a href="mailto:support@citotech.net">Support</a>
            <Link to="/login">Sign in</Link>
          </div>
        </div>
      </div>
      <div className="cito-footer-bottom">
        <span>© {new Date().getFullYear()} Core-Synergies</span>
        <span>Cito Technologies · Business, connected.</span>
      </div>
    </footer>
  );
}
