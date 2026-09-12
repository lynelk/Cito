'use strict';
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const {javaTrim}=require('../Node/cpay-signing');
const root=path.resolve(__dirname,'../..');
const delivered=path.join(root,'Cito-External-API.postman_collection.json');
const file=fs.existsSync(delivered) ? delivered : path.join(root,'Docs/Api/cpay-v2-postman-collection.json');
const collection=JSON.parse(fs.readFileSync(file,'utf8'));
const source=collection.event.find(e=>e.listen==='prerequest').script.exec.join('\n');
const start=source.indexOf('function javaTrim('),end=source.indexOf('\nfunction canonicalQuery(',start);
assert(start>=0 && end>start);
const sandbox={};vm.runInNewContext(source.slice(start,end)+'\nthis.trimUnderTest=javaTrim;',sandbox,{timeout:1000});
let cases=0;
for(const trim of [javaTrim,sandbox.trimUnderTest]) {
  const middle='A'+'\0'.repeat(300000)+'B';assert.equal(trim(middle),middle);cases++;
  assert.equal(trim('\0'.repeat(300000)), '');cases++;
  assert.equal(trim(' \t\0'+middle+'\0\r\n '),middle);cases++;
  assert.equal(trim('\u00a0 A \u00a0'),'\u00a0 A \u00a0');cases++;
  assert.equal(trim('\x21'+ ' '.repeat(300000)+'\x21'),'\x21'+ ' '.repeat(300000)+'\x21');cases++;
}
console.log(JSON.stringify({runtime:'Node and generated Postman linear trim',cases,result:'PASS',providerRequests:0}));
