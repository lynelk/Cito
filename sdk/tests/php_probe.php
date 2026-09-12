<?php
declare(strict_types=1);
require_once __DIR__.'/../Php/CPaySigning.php';
$data=json_decode(stream_get_contents(STDIN),true,512,JSON_THROW_ON_ERROR);
$out=[];
foreach($data['vectors'] as $v) {
 $out[]=['canonical'=>CPaySigning::canonicalString($v['method'],$v['path'],$v['query'],$v['timestamp'],$v['nonce'],$v['body']),
 'headers'=>CPaySigning::signRequest('TEST',$data['key'],$v['method'],$v['path'],$v['query'],$v['body'],$v['timestamp'],$v['nonce'])];
}
echo json_encode($out,JSON_THROW_ON_ERROR|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
