import json
import unittest

from check_solver_models import check_pairs, claude_models, opencode_models


class ModelMetadataTests(unittest.TestCase):
    def test_claude_resolved_model_and_effort(self):
        data = {"type": "control_response", "response": {"response": {"models": [
            {"value": "opus", "resolvedModel": "claude-opus-5-5",
             "supportedEffortLevels": ["medium", "high"]}]}}}
        models = claude_models(json.dumps(data))
        check_pairs(models, [("claude-opus-5-5", "high")])
        with self.assertRaisesRegex(ValueError, "not advertised"):
            check_pairs(models, [("claude-opus-5-6", "high")])

    def test_opencode_does_not_invent_medium_variant(self):
        models = opencode_models('provider/glm\n{"variants":{"low":{},"high":{},"max":{}}}\n'
                                 'provider/muse\n{"variants":{"medium":{},"high":{}}}\n')
        check_pairs(models, [("provider/muse", "high")])
        with self.assertRaisesRegex(ValueError, "does not advertise effort medium"):
            check_pairs(models, [("provider/glm", "medium")])
        with self.assertRaisesRegex(ValueError, "not advertised"):
            check_pairs(models, [("provider/glm-pro", "high")])
        with self.assertRaises(ValueError):
            opencode_models('provider/muse\n{"variants":')


if __name__ == "__main__":
    unittest.main()
