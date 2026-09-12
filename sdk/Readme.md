# Cito consumer SDKs — 2.0.0

**Connect Once. Operate Everything.** Cito is the platform; CPay v2 remains the compatible payment-signing protocol.

Start with `Docs/Api/consumer/START-HERE.md`. These are server-side helpers for Node 22+, Python 3.11+ and PHP 8.2+; they must not be embedded with private keys in browser or mobile applications. The external package contains v2 server-to-server operations only. Merchant-session, administrator and legacy body-signature APIs are separate surfaces.

## Version 2 changes

The constructor requires `environment` (`SANDBOX` or `PRODUCTION`). Native collect/payout methods require a caller-owned idempotency key. Query sorting matches Java UTF-16 ordering of decoded names and repeated values; UTF-8 form encoding uses `+` for spaces, `%2B` for literal plus, `*` unchanged and `%7E` for tilde. The existing verifier trims U+0000–U+0020 at body boundaries; compact JSON avoids ambiguity. No backend protocol change is introduced.

A retry keeps its original reference, body and idempotency key but receives a fresh nonce and timestamp. No method silently retries a timeout, follows a redirect, disables TLS verification or generates a new business-operation key. Read methods may still incur the published access fee. CSV statements return text rather than being parsed as JSON. HTTP errors retain a structured status/payload/request ID; they do not prove a final provider outcome.

## Installation

Copy the selected language directory. Node has no third-party runtime dependencies. For Python, install `sdk/Python/requirements.txt` in a virtual environment. PHP requires OpenSSL, iconv, JSON and cURL; `composer.json` records these prerequisites. Keep your private key in the server's secret store or a protected file, not this directory. Distribute only the public key to Cito through approved onboarding.

## First request: signed discovery, not money movement

Node:

```javascript
const fs = require('node:fs');
const { CPayClient } = require('./sdk/Node/cpay-client');
const client = new CPayClient({
  baseUrl: process.env.CITO_BASE_URL,
  merchantNumber: process.env.CITO_MERCHANT_NUMBER,
  privateKeyPem: fs.readFileSync(process.env.CITO_PRIVATE_KEY_FILE, 'utf8'),
  environment: process.env.CITO_ENVIRONMENT,
});
// Run only after the deployment, environment and API-access price are approved.
async function main() {
  const catalogue = await client.channels();
  console.log(JSON.stringify(catalogue));
}
main().catch(() => { console.error('Discovery failed; inspect the original request status securely.'); process.exitCode = 1; });
```

Python:

```python
import os
from pathlib import Path
from cpay_client import CPayClient
client = CPayClient(
    base_url=os.environ['CITO_BASE_URL'],
    merchant_number=os.environ['CITO_MERCHANT_NUMBER'],
    private_key_pem=Path(os.environ['CITO_PRIVATE_KEY_FILE']).read_text(),
    environment=os.environ['CITO_ENVIRONMENT'],
)
catalogue = client.channels()
```

PHP:

```php
require_once 'sdk/Php/CPayClient.php';
$client = new CPayClient(
    getenv('CITO_BASE_URL'), getenv('CITO_MERCHANT_NUMBER'),
    file_get_contents(getenv('CITO_PRIVATE_KEY_FILE')), getenv('CITO_ENVIRONMENT')
);
$catalogue = $client->channels();
```

## Native collection/payout

Persist the operation key and exact payload in your own durable order/workflow record before the first attempt. Native helpers send `/api/v2/native/payments/collect` and `/payout`; they no longer select the production-default compatibility routes implicitly.

```javascript
// Illustrative only; no provider account is provisioned by this example.
await client.collect(savedOrder.payload, { idempotencyKey: savedOrder.operationKey });
// After an uncertain response, query savedOrder's original Cito reference first.
// Reuse savedOrder.operationKey on a commercially identical authorized retry.
```

Python uses `client.collect(payload, idempotency_key=saved_key)`; PHP uses `$client->collect($payload, $savedKey)`. Monetary amounts must be positive decimal **strings** with no more than four decimal places. Never alter the merchant, amount, currency, reference or environment when replaying an uncertain operation. A `202` response is pending, not settlement.

The signer still supports custom server-to-server calls. Select the operation from `external-openapi.json`, use its declared RSA or BaaS authentication, and preserve its exact environment/authorization contract. The signing helper does not make an administrator or merchant-session operation accessible to a service account.

`validateAccount` and payment-link creation reject SANDBOX in these wrappers because their current implementation has no verified sandbox selector. An arbitrary header is not isolation. BaaS uses its separate `X-Cito-Api-Key` and `X-Cito-Environment` contract, not the CPay RSA signer.

## Tests

```bash
# Install pytest and the Python requirements; Java 21, Node 22+ and PHP are also needed.
python -m pytest sdk/tests -q
node sdk/tests/client-regressions.cjs
php sdk/tests/client-regressions.php
node sdk/tests/postman-regressions.cjs
```

Tests use synthetic requests, injected transports and ephemeral keys. The Java corpus and backend `ConsumerSigningConformanceTest` verify the protocol against the actual backend helper. No provider request or money movement is part of this suite. Read the release evidence for the exact dependency versions and checks that ran; source presence is not deployment or operator certification.

The SDK tests run from the extracted kit root as well as the repository. Repository-only generation checks are not included in the handover archive. `postman-regressions.cjs` exercises the generated script with standard Web Crypto and a local sandbox shim; this is not an automated Postman desktop or live-provider certification.
