#!/usr/bin/env python3
"""One-shot source normalisation for PR #203; removed by its workflow after use."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
admin = root / 'Docs/Api/cito-admin-v2-openapi.yaml'
text = admin.read_text()
marker = '  /api/v2/admin/shared-provider/merchant-credentials:\n'
endpoint = '''  /api/v2/admin/platform-evidence/scorecard:
    get:
      tags:
      - Admin Commercial Programmes
      operationId: getPlatformEvidenceScorecard
      summary: Read evidence-backed platform maturity metrics
      description: |
        Administrator-only read model for durable commercial, developer, adoption, provider
        capability and provider-certification evidence. Missing evidence is not converted into a
        successful state and configured adapters are not treated as production certification.
      responses:
        '200':
          description: Durable platform evidence scorecard
          content:
            application/json:
              schema:
                type: object
                required:
                - evidenceBasis
                - targetsReportedAsActuals
                - commercial
                - developer
                - adoption
                - providerDefinitions
                - providerCertification
                properties:
                  evidenceBasis: {type: string, enum: [DURABLE_RECORDS_ONLY]}
                  targetsReportedAsActuals: {type: boolean, const: false}
                  commercial: {type: object, additionalProperties: {type: integer, format: int64}}
                  developer: {type: object, additionalProperties: {type: integer, format: int64}}
                  adoption: {type: object, additionalProperties: {type: integer, format: int64}}
                  providerDefinitions:
                    type: array
                    items:
                      type: object
                      required: [providerCode, domain, capabilities, environments]
                      properties:
                        providerCode: {type: string}
                        domain: {type: string, enum: [PAYMENT, COMMUNICATION, IDENTITY, RISK, VENDING, REGULATORY]}
                        capabilities: {type: array, items: {type: string}}
                        environments: {type: array, items: {type: string, enum: [SANDBOX, PRODUCTION]}}
                  providerCertification:
                    type: array
                    items: {type: object, additionalProperties: true}
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
'''
if endpoint not in text:
    if marker not in text:
        raise SystemExit('Admin OpenAPI insertion marker changed')
    text = text.replace(marker, endpoint + marker, 1)
if 'version: 2.1.0' in text:
    text = text.replace('version: 2.1.0', 'version: 2.2.0', 1)
admin.write_text(text)

readme = root / 'Docs/Api/README.md'
value = readme.read_text()
note = '\n- `GET /api/v2/admin/platform-evidence/scorecard` exposes administrator-only durable platform evidence; adapter availability and targets are never reported as certification or achieved adoption.\n'
if note.strip() not in value:
    readme.write_text(value.rstrip() + '\n' + note)

print('Platform evidence OpenAPI and API documentation updated')
