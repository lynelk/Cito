<?php
declare(strict_types=1);
/** Cito consumer SDK 2.0; wire-compatible with the existing CPay v2 verifier. */
final class CPaySigning
{
    public static function javaTrim(string $value): string { return preg_replace('/^[\x00-\x20]+|[\x00-\x20]+$/', '', $value); }
    public static function sha256Hex(string $value): string { return hash('sha256', $value); }
    private static function scalar(mixed $value): string {
        if ((!is_string($value) && !is_int($value)) || preg_match('//u', (string)$value) !== 1) {
            throw new InvalidArgumentException('Query values must be valid UTF-8 strings or integers; amounts use decimal strings');
        }
        return (string)$value;
    }
    public static function canonicalQuery(array $query = []): string {
        $entries = array_is_list($query) ? $query : array_map(fn($k,$v) => [$k,$v], array_keys($query), array_values($query));
        $pairs = [];
        foreach ($entries as $entry) {
            if (!is_array($entry) || count($entry) !== 2) throw new InvalidArgumentException('Expected name/value pairs');
            [$key,$value] = $entry;
            if (!is_string($key)) throw new InvalidArgumentException('Query names must be strings');
            foreach (is_array($value) ? $value : [$value] as $item) {
                if ($item !== null) $pairs[] = [self::scalar($key), self::scalar($item)];
            }
        }
        usort($pairs, function($a,$b) {
            $key = strcmp(iconv('UTF-8','UTF-16BE',$a[0]), iconv('UTF-8','UTF-16BE',$b[0]));
            return $key ?: strcmp(iconv('UTF-8','UTF-16BE',$a[1]), iconv('UTF-8','UTF-16BE',$b[1]));
        });
        $encode = fn($v) => str_replace('%2A', '*', urlencode($v));
        return implode('&', array_map(fn($p) => $encode($p[0]).'='.$encode($p[1]), $pairs));
    }
    public static function canonicalString(string $method,string $path,array $query,string $timestamp,string $nonce,string $body=''): string {
        return implode("\n", [strtoupper(self::javaTrim($method)),self::javaTrim($path),self::canonicalQuery($query),self::javaTrim($timestamp),self::javaTrim($nonce),self::sha256Hex(self::javaTrim($body))]);
    }
    public static function headerValue(string $value,string $name): string {
        if ($value === '' || strlen($value)>256 || preg_match('/[\x00-\x20\x7f]/',$value)) throw new InvalidArgumentException($name.' must be a bounded nonempty header without whitespace');
        return $value;
    }
    public static function signRequest(string $merchantNumber,string $privateKeyPem,string $method,string $path,array $query=[],string $body='',?string $timestamp=null,?string $nonce=null,?string $idempotencyKey=null): array {
        self::headerValue($merchantNumber,'merchantNumber');
        $timestamp=self::headerValue($timestamp ?? gmdate('Y-m-d\TH:i:s\Z'),'timestamp');
        $nonce=self::headerValue($nonce ?? bin2hex(random_bytes(16)),'nonce');
        if ($idempotencyKey!==null) self::headerValue($idempotencyKey,'idempotencyKey');
        $key=openssl_pkey_get_private($privateKeyPem);
        $details=$key ? openssl_pkey_get_details($key) : false;
        if (!$details || $details['type']!==OPENSSL_KEYTYPE_RSA || $details['bits']<2048) throw new InvalidArgumentException('RSA private key of at least 2048 bits required');
        $canonical=self::canonicalString($method,$path,$query,$timestamp,$nonce,$body);
        if (!openssl_sign($canonical,$signature,$key,OPENSSL_ALGO_SHA256)) throw new RuntimeException('Cito signing failed');
        $headers=['X-CPay-Merchant-Number'=>$merchantNumber,'X-CPay-Signature-Version'=>'v2','X-CPay-Timestamp'=>$timestamp,'X-CPay-Nonce'=>$nonce,'X-CPay-Signature'=>base64_encode($signature)];
        if ($idempotencyKey!==null) $headers['X-CPay-Idempotency-Key']=$idempotencyKey;
        return $headers;
    }
}
