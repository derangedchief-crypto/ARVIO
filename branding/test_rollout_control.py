import unittest
from check_rollout_control import ready

class RolloutControlTests(unittest.TestCase):
    def test_compatible_installed_control(self):
        self.assertTrue(ready({'configured':True,'installed':True,'version':'0.36.0'}))
        self.assertTrue(ready({'configured':True,'installed':True,'version':'0.37.0'}))
    def test_old_uninstalled_or_malformed_control_is_refused(self):
        for value in [None,{}, {'configured':True,'installed':True,'version':'0.35.0'}, {'configured':True,'installed':False,'version':'0.36.0'}, {'configured':False,'installed':True,'version':'0.36.0'}, {'configured':'true','installed':True,'version':'0.36.0'}, {'configured':True,'installed':True,'version':'v0.36.0'}]:self.assertFalse(ready(value))
