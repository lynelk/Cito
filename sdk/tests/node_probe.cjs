"use strict";
const fs = require('node:fs');
const s = require('../Node/cpay-signing');
const { CPayClient } = require('../Node/cpay-client');
(async () => {
  const data = JSON.parse(fs.readFileSync(0,'utf8'));
  const result = data.vectors.map(v => ({canonical: s.canonicalString(v), headers:s.signRequest({...v,merchantNumber:'TEST',privateKeyPem:data.key})}));
  process.stdout.write(JSON.stringify(result));
})().catch(e => { console.error(e.name, e.message); process.exit(1); });
