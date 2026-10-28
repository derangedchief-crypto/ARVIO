<?php
declare(strict_types=1);
namespace ControlCenter;

/** Format shared with the activation worker. No proof-signing key lives in APKs. */
final class EnrollmentProof
{
    public const PACKAGES = ['com.arvio.tv', 'com.arvio.tv.beta', 'com.arvio.tv.test'];
    public function __construct(private readonly Config $config, private readonly Secrets $secrets,private readonly ?BrandArtifacts $artifacts=null) {}
    public function bridgeSecret(): string { return $this->secrets->mac('management-enrollment-bridge:v1'); }
    public static function package(mixed $package): string
    {
        if (!is_string($package) || !in_array($package, self::PACKAGES, true)) throw new Problem(422, 'Unsupported app package.');
        return $package;
    }
    public function admittedPackage(mixed $package):string {
        if(is_string($package)&&in_array($package,self::PACKAGES,true))return $package;
        $approved=$this->artifacts?->enrollmentPackage($package);if($approved!==null)return $approved;
        throw new Problem(422,'Unsupported or unpublished app package.');
    }
    public static function hex(mixed $value, int $length): string
    {
        if (!is_string($value) || !preg_match('/^[a-f0-9]{'.$length.'}$/D', $value)) throw new Problem(422, 'Invalid enrollment identity.');
        return $value;
    }
    private static function decode(string $value): string
    {
        if (!preg_match('/^[A-Za-z0-9_-]+$/D', $value) || strlen($value) % 4 === 1) throw new Problem(403, 'Invalid enrollment proof.');
        $decoded = base64_decode(strtr($value,'-_','+/') . str_repeat('=',(4-strlen($value)%4)%4),true);
        if ($decoded === false) throw new Problem(403, 'Invalid enrollment proof.');
        return $decoded;
    }
    public function verify(string $token, ?int $now = null): array
    {
        if (strlen($token)>4096) throw new Problem(403, 'Invalid enrollment proof.');
        $parts=explode('.',$token);
        if(count($parts)!==3||$parts[0]!=='ec1') throw new Problem(403,'Invalid enrollment proof.');
        $expected=hash_hmac('sha256','proof-v1.'.$parts[1],hex2bin($this->bridgeSecret()),true);
        if(!hash_equals($expected,self::decode($parts[2]))) throw new Problem(403,'Invalid enrollment proof.');
        try { $claim=json_decode(self::decode($parts[1]),true,16,JSON_THROW_ON_ERROR); }
        catch(\JsonException) { throw new Problem(403,'Invalid enrollment proof.'); }
        if(!is_array($claim)||array_is_list($claim)) throw new Problem(403,'Invalid enrollment proof.');
        Input::keys($claim,['v','iss','aud','sub','hint','install','key','package','challenge','iat','exp','jti','panel']);
        $now ??= time();
        if(($claim['v']??null)!==1||($claim['iss']??null)!=='extreme-activation'||($claim['aud']??null)!==$this->config->origin()
            ||!is_int($claim['iat']??null)||!is_int($claim['exp']??null)||$claim['iat']>$now+30||$claim['iat']<$now-150
            ||$claim['exp']<$now-15||$claim['exp']<=$claim['iat']||$claim['exp']-$claim['iat']>120) {
            throw new Problem(403,'Enrollment proof expired or has the wrong destination. Request a new proof.');
        }
        foreach(['sub','key','challenge'] as $field) self::hex($claim[$field]??null,64);
        self::hex($claim['install']??null,32); self::hex($claim['jti']??null,32); $this->admittedPackage($claim['package']??null);
        $claim['hint']=Input::text($claim['hint']??'', 'account hint',40);
        if(isset($claim['panel'])) {
            $panel=$claim['panel'];
            if(!is_array($panel)) throw new Problem(403,'Invalid panel ownership.');
            Input::keys($panel,['customer_id','reseller_id','reseller_username']);
            foreach(['customer_id','reseller_id'] as $field) {
                if(!is_string($panel[$field]??null)||!preg_match('/^[1-9][0-9]{0,19}$/D',$panel[$field])) throw new Problem(403,'Invalid panel ownership.');
            }
            Input::text($panel['reseller_username']??null,'panel username',150);
        }
        return $claim;
    }

    public static function publicKey(string $encoded): array
    {
        if(strlen($encoded)>256||!preg_match('~^[A-Za-z0-9+/]+={0,2}$~D',$encoded)) throw new Problem(422,'Invalid device public key.');
        $der=base64_decode($encoded,true);
        if($der===false||base64_encode($der)!==$encoded) throw new Problem(422,'Invalid device public key.');
        $pem="-----BEGIN PUBLIC KEY-----\n".chunk_split($encoded,64,"\n")."-----END PUBLIC KEY-----\n";
        $key=@openssl_pkey_get_public($pem);
        $details=$key ? openssl_pkey_get_details($key) : false;
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??'')!=='prime256v1') throw new Problem(422,'Use a P-256 device key.');
        return ['key'=>$key,'fingerprint'=>hash('sha256',$der)];
    }
    public static function signingText(string $nonce, string $proof, string $install, string $package, string $fingerprint,?string $recoveryHint=null): string
    {
        if($recoveryHint!==null)return "ECENROLL2\n$nonce\n".hash('sha256',$proof)."\n$install\n$package\n$fingerprint\n$recoveryHint";
        return "ECENROLL1\n$nonce\n".hash('sha256',$proof)."\n$install\n$package\n$fingerprint";
    }
    public static function verifyDeviceSignature(array $key, string $signature, string $text): void
    {
        if(strlen($signature)>128) throw new Problem(403,'Invalid device signature.');
        $bytes=base64_decode($signature,true);
        if($bytes===false||@openssl_verify($text,$bytes,$key['key'],OPENSSL_ALGO_SHA256)!==1) throw new Problem(403,'Invalid device signature.');
    }
}
