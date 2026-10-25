import json
from pathlib import Path
import unittest
from unittest.mock import Mock

from branding.test_central_builder import cb, fixture


class BuildProgressTests(unittest.TestCase):
    def test_legacy_website_does_not_receive_unnegotiated_phase_fields(self):
        bridge=Mock();heartbeat=cb.Heartbeat(bridge,fixture());heartbeat.stage('building');bridge.call.assert_not_called()

    def test_negotiated_stages_are_leased_and_preparation_stops_stage_reporting(self):
        context=dict(fixture(),progress_protocol=1);bridge=Mock();heartbeat=cb.Heartbeat(bridge,context)
        for stage in ['preparing','building','verifying','retaining','publishing','mirroring']:
            heartbeat.stage(stage);bridge.call.assert_called_with('heartbeat',dict(cb.lease(context),phase=stage))
        heartbeat.prepared({'synthetic':'receipt'},False);bridge.call.assert_called_with('prepared',dict(cb.lease(context),receipt={'synthetic':'receipt'},release_lease=False));self.assertIsNone(heartbeat.current_phase)
        bridge.reset_mock();heartbeat.stop=Mock();heartbeat.stop.wait.side_effect=[False,True];heartbeat.loop();bridge.call.assert_called_once_with('heartbeat',cb.lease(context))

    def test_bad_stages_and_lost_leases_cannot_claim_progress_or_completion(self):
        bridge=Mock();heartbeat=cb.Heartbeat(bridge,dict(fixture(),progress_protocol=1))
        with self.assertRaises(ValueError):heartbeat.stage('complete')
        heartbeat.lost=True
        with self.assertRaises(RuntimeError):heartbeat.stage('publishing')
        bridge.call.assert_not_called()

    def test_bootstrap_eta_uses_existing_measured_runs_not_new_dummy_builds(self):
        root=Path(__file__).resolve().parent.parent;data=json.loads((root/'control-center/deploy/brand-build-timings.json').read_text())
        self.assertEqual('testing',data['channel']);self.assertEqual([607,620,1087],data['build_seconds']);self.assertEqual([61,43],data['publication_seconds']);self.assertEqual(3,len(data['build_run_ids']))


if __name__=='__main__':unittest.main()
