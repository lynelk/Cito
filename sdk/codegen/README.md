# Optional generated clients

The supported, conformance-tested handover is `Docs/Api/consumer/START-HERE.md` plus `sdk/Node`, `sdk/Python` and `sdk/Php`. The wrappers deliberately expose common payment/discovery methods; their signing helpers support other documented merchant-signed operations. Billing/BaaS uses its separate API-key contract.

This optional scaffold is not shipped as a tested full client. It uses only the **filtered external v2 OpenAPI** at `Docs/Api/consumer/external-openapi.json`. Do not generate an external integration from the administrator runtime schema or expose Swagger UI to obtain one. No production setting needs to change.

Install an approved OpenAPI Generator CLI from its official distribution, confirm that its selected version supports the OpenAPI 3.1 document and is acceptable to your software supply-chain policy, and run `bash sdk/codegen/generate.sh`. The script does not download a version implicitly. Set `OPENAPI_GENERATOR_CLI` to an installed executable path when it is not on PATH. Generated output remains ignored local build output, not reviewed release source.

Generated HTTP methods still require a correct RSA signing interceptor or the separately scoped BaaS API key. They must preserve explicit environment, original idempotency keys, exact transmitted bodies, redirect rejection, timeouts and tenant boundaries. Run the supplied shared signing vectors and your endpoint acceptance tests before adopting a generated client. Generation success is not authentication or production-provider certification.
