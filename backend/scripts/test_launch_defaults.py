import unittest

from launch_defaults import (
    AGREEMENT_ARTICLES,
    BLOCKED_REGIONS,
    REGION_POLICIES,
    SUPPORTED_MAINLAND_REGIONS,
    UNSUPPORTED_REGIONS,
    article_payload,
)


class LaunchDefaultsTest(unittest.TestCase):
    def test_shipping_policy_covers_all_province_level_regions(self):
        self.assertEqual(
            set(REGION_POLICIES),
            set(SUPPORTED_MAINLAND_REGIONS) | set(BLOCKED_REGIONS) | set(UNSUPPORTED_REGIONS),
        )

    def test_shipping_policy_is_free_except_explicitly_blocked_regions(self):
        for code in SUPPORTED_MAINLAND_REGIONS:
            self.assertEqual(REGION_POLICIES[code], {"enabled": True, "freightFen": 0, "freeThresholdFen": None})
        for code in set(BLOCKED_REGIONS) | set(UNSUPPORTED_REGIONS):
            self.assertEqual(REGION_POLICIES[code], {"enabled": False, "freightFen": 0, "freeThresholdFen": None})

    def test_agreements_are_publishable_and_do_not_contain_placeholders(self):
        self.assertEqual({article["kind"] for article in AGREEMENT_ARTICLES}, {"terms", "privacy"})
        for article in AGREEMENT_ARTICLES:
            self.assertEqual(article["status"], "PUBLISHED")
            self.assertTrue(article["contentBlocks"])
            text = "\n".join(block["text"] for block in article["contentBlocks"])
            self.assertNotIn("待替换", text)
            self.assertNotIn("TODO", text)
            self.assertIn("小程序内客服入口", text)

    def test_new_article_payload_starts_at_version_zero(self):
        payload = article_payload(AGREEMENT_ARTICLES[0])
        self.assertEqual(payload["version"], 0)
        self.assertEqual(payload["status"], "PUBLISHED")


if __name__ == "__main__":
    unittest.main()
