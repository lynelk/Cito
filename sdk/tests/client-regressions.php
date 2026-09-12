<?php
declare(strict_types=1);
require_once __DIR__.'/../Php/CPayClient.php';
function check(bool $condition): void { if (!$condition) throw new RuntimeException('Regression assertion failed'); }
function rejects(callable $operation,string $type=Throwable::class): void { try {$operation();} catch(Throwable $e) {check($e instanceof $type);return;} throw new RuntimeException('Expected rejection'); }
$key=openssl_pkey_new(['private_key_bits'=>2048,'private_key_type'=>OPENSSL_KEYTYPE_RSA]);openssl_pkey_export($key,$pem);
$calls=[];$response=['status'=>202,'headers'=>['content-type'=>'application/json'],'body'=>'{"status":"PENDING"}'];
$transport=function($r)use(&$calls,&$response){$calls[]=$r;return $response;};
$c=new CPayClient('https://cito.test','TEST',$pem,'SANDBOX',30000,$transport);
$p=['amount'=>'12.3456','reference'=>'order-1','currency'=>'UGX','country'=>'UG','channel'=>'test'];$cases=0;
foreach(['http://cito.test','https://u:p@cito.test','https://cito.test/path','https://not-configured.invalid','https://cito.test?x=y'] as $origin){rejects(fn()=>new CPayClient($origin,'TEST',$pem,'SANDBOX'));$cases++;}
rejects(fn()=>new CPayClient('https://cito.test','TEST',$pem,'TYPO'));$cases++;
rejects(fn()=>$c->collect($p,''));$cases++;
rejects(fn()=>$c->collect([...$p,'merchantNumber'=>'OTHER'],'i'));$cases++;
rejects(fn()=>$c->collect([...$p,'amount'=>12.34],'i'));$cases++;
rejects(fn()=>$c->collect([...$p,'metadata'=>['environment'=>'PRODUCTION']],'i'));$cases++;
check(count($calls)===0);
$c->collect($p,'operation-key');$c->collect($p,'operation-key');
check($calls[0]['url']==='https://cito.test/api/v2/native/payments/collect');
check($calls[0]['headers']['X-CPay-Environment']==='SANDBOX');
check($calls[0]['headers']['X-CPay-Idempotency-Key']===$calls[1]['headers']['X-CPay-Idempotency-Key']);
check($calls[0]['headers']['X-CPay-Nonce']!==$calls[1]['headers']['X-CPay-Nonce']);
check($calls[0]['body']===$calls[1]['body'] && $calls[0]['followRedirects']===false && $calls[0]['verifyTls']===true && $calls[0]['timeoutMs']===30000);$cases+=4;
$c->webhookEvents();check(str_ends_with($calls[2]['url'],'/webhooks/events?merchantNumber=TEST'));check(isset($calls[2]['headers']['X-CPay-Signature']));$cases++;
$response=['status'=>200,'headers'=>['content-type'=>'text/csv'],'body'=>"x,y\na,b\n"];check($c->statements('2026-01-01','2026-01-02','csv')==="x,y\na,b\n");$cases++;
$response=['status'=>204,'headers'=>[],'body'=>''];check($c->channels()===null);$cases++;
foreach([301,302,307,401,403,409,429,500] as $status){$response=['status'=>$status,'headers'=>['content-type'=>'application/json'],'body'=>'{}'];$before=count($calls);rejects(fn()=>$c->channels(),CPayError::class);check(count($calls)===$before+1);$cases++;}
$response=['status'=>200,'headers'=>['content-type'=>'application/json'],'body'=>'broken'];rejects(fn()=>$c->channels(),CPayTransportError::class);$cases++;
rejects(fn()=>$c->createPaymentLink([],'i'));rejects(fn()=>$c->validateAccount([]));$cases+=2;
echo json_encode(['runtime'=>'PHP','cases'=>$cases,'providerRequests'=>0,'result'=>'PASS'])."\n";
