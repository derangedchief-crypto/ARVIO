from datetime import datetime,timezone
import unittest
from dispatch_approved_customer_release import allowed_time,check_ready

SHA='a'*40
WEBSITE={'configured':True,'installed':True,'version':'0.37.0'}
GRADLE='val platformVersionName = "2.0.131"\nval platformVersionCode = 447\n'

class ScheduledCustomerReleaseTests(unittest.TestCase):
    def test_fall_back_date_uses_three_thirty_est_not_edt_or_repeated_one_am(self):
        self.assertTrue(allowed_time(datetime(2026,11,1,8,30,tzinfo=timezone.utc)))
        for t in [datetime(2026,11,1,7,30,tzinfo=timezone.utc),datetime(2026,11,1,8,29,tzinfo=timezone.utc),datetime(2026,11,1,8,40,tzinfo=timezone.utc),datetime(2027,11,1,8,30,tzinfo=timezone.utc)]:self.assertFalse(allowed_time(t))
    def test_exact_source_operator_website_and_version_are_required(self):
        check_ready(SHA,SHA,SHA,WEBSITE,GRADLE)
        for args in [(SHA,'b'*40,SHA,WEBSITE,GRADLE),(SHA,SHA,'b'*40,WEBSITE,GRADLE),(SHA,SHA,SHA,{**WEBSITE,'version':'0.36.0'},GRADLE),(SHA,SHA,SHA,WEBSITE,GRADLE.replace('447','446'))]:
            with self.assertRaises(ValueError):check_ready(*args)

if __name__=='__main__':unittest.main()
