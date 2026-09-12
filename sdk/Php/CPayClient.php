<?php
declare(strict_types=1);
require_once __DIR__.'/CPaySigning.php';

final class CPayError extends RuntimeException {
    public function __construct(public int $statusCode, public mixed $payload, public ?string $requestId=null) {
        parent::__construct('Cito returned HTTP '.$statusCode.'; inspect the response and original operation status');
    }
}
final class CPayTransportError extends RuntimeException {}

/** Explicit environment and operation key; no automatic retry or redirect; TLS remains verified. */
final class CPayClient {
    private string $baseUrl;
    private ?Closure $transport;
    public function __construct(string $baseUrl,private string $merchantNumber,private string $privateKeyPem,private string $environment,private int $timeoutMs=30000,?callable $transport=null) {
        $url=parse_url($baseUrl);
        if (!$url || ($url['scheme']??'')!=='https' || empty($url['host']) || isset($url['user']) || isset($url['pass']) || !in_array($url['path']??'',['','/'],true) || isset($url['query']) || isset($url['fragment']) || preg_match('/[\x00-\x20\x7f\\\\]/',$baseUrl) || str_ends_with($url['host'],'.invalid') || str_contains($baseUrl,'YOUR') || str_contains($baseUrl,'{{')) throw new InvalidArgumentException('Use the approved HTTPS origin without placeholders, path, query or credentials');
        CPaySigning::headerValue($merchantNumber,'merchantNumber');
        if (!in_array($environment,['SANDBOX','PRODUCTION'],true)) throw new InvalidArgumentException('environment must explicitly be SANDBOX or PRODUCTION');
        if ($timeoutMs<1 || $timeoutMs>120000 || $privateKeyPem==='') throw new InvalidArgumentException('Private key and timeout of 1..120000ms required');
        $this->baseUrl=rtrim($baseUrl,'/'); $this->transport=$transport===null ? null : Closure::fromCallable($transport);
    }
    public function collect(array $request,string $idempotencyKey): mixed { return $this->payment('collect',$request,$idempotencyKey); }
    public function payout(array $request,string $idempotencyKey): mixed { return $this->payment('payout',$request,$idempotencyKey); }
    private function payment(string $operation,array $request,string $key): mixed {
        CPaySigning::headerValue($key,'idempotencyKey');
        foreach (['reference','currency','country','channel'] as $field) if (!is_string($request[$field]??null) || trim($request[$field])==='') throw new InvalidArgumentException($field.' is required');
        if (!is_string($request['amount']??null) || !preg_match('/^\d+(?:\.\d{1,4})?$/D',$request['amount']) || !preg_match('/[1-9]/',$request['amount'])) throw new InvalidArgumentException('amount must be a positive decimal string with at most four decimals');
        return $this->post('/api/v2/native/payments/'.$operation,$request,$key);
    }
    public function channels(): mixed { return $this->get('/api/v2/channels'); }
    public function webhookEvents(): mixed { return $this->get('/api/v2/webhooks/events'); }
    public function identityCapabilities(): mixed { return $this->get('/api/v2/identity/capabilities'); }
    public function balances(): mixed { return $this->get('/api/v2/balances'); }
    public function status(string $reference): mixed {
        if (!preg_match('/^[A-Za-z0-9_-][A-Za-z0-9._:-]{0,127}$/D',$reference)) throw new InvalidArgumentException('Use the original URL-safe reference returned by Cito');
        return $this->get('/api/v2/payments/'.rawurlencode($reference));
    }
    public function statements(string $startDate,string $endDate,string $format='json',?int $limit=null): mixed {
        if (!in_array($format,['json','csv'],true)) throw new InvalidArgumentException('format must be json or csv');
        return $this->get('/api/v2/statements',['startDate'=>$startDate,'endDate'=>$endDate,'format'=>$format,'limit'=>$limit]);
    }
    public function validateAccount(array $request): mixed { $this->productionOnly(); return $this->post('/api/v2/accounts/validate',$request); }
    public function createPaymentLink(array $request,string $idempotencyKey): mixed { $this->productionOnly(); CPaySigning::headerValue($idempotencyKey,'idempotencyKey'); return $this->post('/api/v2/payment-links',$request,$idempotencyKey); }
    private function productionOnly(): void { if ($this->environment!=='PRODUCTION') throw new InvalidArgumentException('This operation has no verified sandbox selection contract'); }
    private function post(string $path,array $request,?string $key=null): mixed {
        if (isset($request['merchantNumber']) && $request['merchantNumber']!==$this->merchantNumber) throw new InvalidArgumentException('Request merchant mismatch');
        if (isset($request['metadata']) && (!is_array($request['metadata']) || (isset($request['metadata']['environment']) && $request['metadata']['environment']!==$this->environment))) throw new InvalidArgumentException('Conflicting metadata environment');
        $request['merchantNumber']=$this->merchantNumber;
        $body=json_encode($request,JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR);
        $headers=CPaySigning::signRequest($this->merchantNumber,$this->privateKeyPem,'POST',$path,[],$body,null,null,$key);
        $headers['Content-Type']='application/json'; $headers['X-CPay-Environment']=$this->environment;
        return $this->send('POST',$path,$headers,$body);
    }
    private function get(string $path,array $query=[]): mixed {
        $query['merchantNumber']=$this->merchantNumber;
        $headers=CPaySigning::signRequest($this->merchantNumber,$this->privateKeyPem,'GET',$path,$query);
        $headers['X-CPay-Environment']=$this->environment;
        return $this->send('GET',$path.'?'.CPaySigning::canonicalQuery($query),$headers,'');
    }
    private function send(string $method,string $path,array $headers,string $body): mixed {
        $options=['method'=>$method,'url'=>$this->baseUrl.$path,'headers'=>$headers,'body'=>$body,'timeoutMs'=>$this->timeoutMs,'followRedirects'=>false,'verifyTls'=>true];
        if ($this->transport) {
            $response=($this->transport)($options); // Trusted injectable transport for offline tests.
        } else {
            if (!function_exists('curl_init')) throw new RuntimeException('The PHP cURL extension is required');
            $curl=curl_init($options['url']); $responseHeaders=[];
            $curlOptions=[CURLOPT_CUSTOMREQUEST=>$method,CURLOPT_RETURNTRANSFER=>true,CURLOPT_FOLLOWLOCATION=>false,
                CURLOPT_CONNECTTIMEOUT_MS=>min(5000,$this->timeoutMs),CURLOPT_TIMEOUT_MS=>$this->timeoutMs,
                CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,
                CURLOPT_HTTPHEADER=>array_map(fn($k,$v)=>$k.': '.$v,array_keys($headers),array_values($headers)),
                CURLOPT_HEADERFUNCTION=>function($curl,$line) use (&$responseHeaders) { if (str_contains($line,':')) { [$k,$v]=explode(':',$line,2); $responseHeaders[strtolower(trim($k))]=trim($v); } return strlen($line); }];
            if ($method!=='GET') $curlOptions[CURLOPT_POSTFIELDS]=$body;
            curl_setopt_array($curl,$curlOptions);
            $text=curl_exec($curl); $status=(int)curl_getinfo($curl,CURLINFO_HTTP_CODE); curl_close($curl);
            if ($text===false) throw new CPayTransportError('Transport failed or timed out; outcome may be unknown. Query the original reference and retain its idempotency key.');
            $response=['status'=>$status,'headers'=>$responseHeaders,'body'=>$text];
        }
        $responseHeaders=array_change_key_case($response['headers']??[],CASE_LOWER);
        $payload=$response['body']==='' ? null : $response['body'];
        if (str_contains(strtolower($responseHeaders['content-type']??''),'json')) {
            try { $payload=json_decode($response['body'],true,512,JSON_THROW_ON_ERROR); }
            catch (JsonException) { throw new CPayTransportError('Malformed JSON response; do not infer transaction completion'); }
        }
        if ($response['status']<200 || $response['status']>=300) throw new CPayError($response['status'],$payload,$responseHeaders['x-request-id']??null);
        return $payload;
    }
}
