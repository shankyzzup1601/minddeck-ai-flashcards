import os
import unittest
from unittest.mock import patch

import app as minddeck


class MindDeckSecurityTests(unittest.TestCase):
    def setUp(self):
        minddeck.app.config["TESTING"] = True
        self.client = minddeck.app.test_client()
        minddeck._rate_buckets.clear()

    def test_home_never_renders_an_api_key_field(self):
        response = self.client.get("/")
        page = response.get_data(as_text=True)

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('id="apiKey"', page)
        self.assertIn('id="accessCode"', page)
        self.assertIn("Content-Security-Policy", response.headers)
        self.assertEqual(response.headers["X-Frame-Options"], "DENY")

    def test_online_providers_stay_locked_without_server_secrets(self):
        with patch.dict(os.environ, {}, clear=True):
            response = self.client.get("/api/config")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["providers"], {"openai": False, "gemini": False})
        self.assertEqual(response.headers["Cache-Control"], "no-store, max-age=0")

    def test_cross_site_ai_request_is_blocked(self):
        response = self.client.post(
            "/api/generate",
            json={"provider": "openai", "text": "A long enough set of notes for testing."},
            headers={"Origin": "https://attacker.example"},
        )

        self.assertEqual(response.status_code, 403)

    def test_browser_api_keys_are_rejected(self):
        response = self.client.post(
            "/api/generate",
            json={
                "provider": "openai",
                "text": "A long enough set of notes for testing.",
                "apiKey": "must-not-be-accepted",
            },
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("server configuration", response.json["error"])

    def test_access_code_is_required_and_provider_key_stays_server_side(self):
        captured = {}

        def fake_post(url, payload, headers):
            captured.update(url=url, payload=payload, headers=headers)
            return {
                "choices": [
                    {
                        "message": {
                            "content": '{"cards":[{"front":"Q","back":"A"}]}'
                        }
                    }
                ]
            }

        environment = {
            "OPENAI_API_KEY": "server-only-test-key",
            "AI_ACCESS_CODE": "a-secure-test-code",
        }
        with patch.dict(os.environ, environment, clear=True), patch.object(
            minddeck, "post_json", side_effect=fake_post
        ):
            denied = self.client.post(
                "/api/generate",
                json={
                    "provider": "openai",
                    "text": "Photosynthesis converts light into stored chemical energy.",
                    "accessCode": "wrong-code",
                },
            )
            allowed = self.client.post(
                "/api/generate",
                json={
                    "provider": "openai",
                    "text": "Photosynthesis converts light into stored chemical energy.",
                    "accessCode": "a-secure-test-code",
                },
            )

        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.json["cards"], [{"front": "Q", "back": "A"}])
        self.assertEqual(captured["headers"]["Authorization"], "Bearer server-only-test-key")
        self.assertNotIn("server-only-test-key", allowed.get_data(as_text=True))


if __name__ == "__main__":
    unittest.main()
