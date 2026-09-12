import React from 'react';

/** A local documentation filter, never an automatic paid request or a provider transaction. */
export default function DeveloperQuickstart({ onExplore }: { onExplore: () => void }): React.ReactElement {
  return <aside aria-label="Safe developer quickstart" className="cito-api-guide">
    <h3>Start with a non-money integration check</h3>
    <p><a href="https://github.com/lynelk/Cito/tree/main/Docs/Api/consumer" target="_blank" rel="noopener noreferrer">Open the external developer handover kit</a>. It separates server-to-server APIs from merchant-session and administrator operations.</p>
    <p>Confirm the approved deployment and account environment, select a capability or status read, review its authentication and current access price, then make an explicitly approved request.</p>
    <p>This workbench uses its connected deployment; opening it does not create a sandbox. A read may be billable. Never use a payout, collection, vending purchase or message send as a documentation connectivity test.</p>
    <button type="button" onClick={onExplore}>Explore capability documentation</button>
    <p>The button only filters this reference. It does not call an API, move funds or transmit credentials.</p>
  </aside>;
}
