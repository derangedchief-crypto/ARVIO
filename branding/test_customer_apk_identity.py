import unittest
import verify_customer_apk as policy

class CustomerApkIdentityTests(unittest.TestCase):
    def setUp(self):
        self.badging="package: name='com.arvio.tv.beta' versionCode='446' versionName='2.0.130-beta'\nsdkVersion:'26'\n"
        self.cert='Signer #1 certificate SHA-256 digest: '+policy.SIGNER
        self.entries=['lib/armeabi-v7a/decoder.so','lib/arm64-v8a/decoder.so']
    def test_official_identity_is_accepted(self):policy.verify('2.0.130',446,self.badging,self.cert,self.entries)
    def test_brand_testing_base_or_wrong_version_cannot_become_a_customer_update(self):
        for old,new in [('com.arvio.tv.beta','com.extreme.brand.b'+'a'*32),('com.arvio.tv.beta','com.arvio.tv.test'),('com.arvio.tv.beta','com.arvio.tv'),('446','445'),('2.0.130-beta','2.0.129-beta')]:
            with self.assertRaises(ValueError):policy.verify('2.0.130',446,self.badging.replace(old,new),self.cert,self.entries)
    def test_wrong_or_unverified_signer_is_refused(self):
        for cert in ['',self.cert.replace(policy.SIGNER,'f'*64),self.cert+'\nSigner #2 certificate SHA-256 digest: '+'f'*64]:
            with self.assertRaises(ValueError):policy.verify('2.0.130',446,self.badging,cert,self.entries)
    def test_missing_abi_or_raised_sdk_is_refused(self):
        with self.assertRaises(ValueError):policy.verify('2.0.130',446,self.badging,self.cert,self.entries[:1])
        with self.assertRaises(ValueError):policy.verify('2.0.130',446,self.badging.replace("'26'","'27'"),self.cert,self.entries)
