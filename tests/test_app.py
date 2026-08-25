import os
import re
import unittest
from unittest.mock import patch

from werkzeug.security import generate_password_hash

import app as minddeck


class MindDeckSecurityTests(unittest.TestCase):
    base_url = "https://minddeck.test"
    user_agent = "MindDeck Security Test"

    def setUp(self):
        minddeck.app.config["TESTING"] = True
        self.client = minddeck.app.test_client()
        minddeck._rate_buckets.clear()

    def home(self):
        response = self.client.get(
            "/",
            base_url=self.base_url,
            headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
        )
        match = re.search(r'<meta name="csrf-token" content="([^"]+)">', response.get_data(as_text=True))
        self.assertIsNotNone(match)
        return response, match.group(1)

    def post(self, path, payload, csrf, origin="https://minddeck.test"):
        return self.client.post(
            path,
            base_url=self.base_url,
            json=payload,
            headers={
                "Origin": origin,
                "User-Agent": self.user_agent,
                "X-CSRF-Token": csrf,
                "X-Forwarded-Proto": "https",
            },
        )

    @staticmethod
    def secure_environment():
        return {
            "OPENAI_API_KEY": "server-only-test-key",
            "AI_ACCESS_CODE_HASH": generate_password_hash("a-very-strong-test-code", method="scrypt"),
            "AI_SESSION_SECRET": "s" * 48,
        }

    def test_home_has_no_browser_api_key_or_third_party_script(self):
        response, _csrf = self.home()
        page = response.get_data(as_text=True)

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('id="apiKey"', page)
        self.assertIn('id="accessCode"', page)
        self.assertNotIn("unpkg.com", page)
        self.assertNotIn("cdnjs.cloudflare.com", page)
        self.assertIn('/static/app.js', page)
        self.assertEqual(response.headers["X-Frame-Options"], "DENY")
        self.assertEqual(response.headers["Cross-Origin-Embedder-Policy"], "require-corp")
        self.assertIn("script-src 'self' 'nonce-", response.headers["Content-Security-Policy"])
        self.assertIn("style-src-attr 'none'", response.headers["Content-Security-Policy"])
        self.assertIn("require-trusted-types-for 'script'", response.headers["Content-Security-Policy"])
        self.assertIn("HttpOnly", response.headers["Set-Cookie"])
        self.assertIn("Secure", response.headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", response.headers["Set-Cookie"])

    def test_online_providers_fail_closed_without_all_secrets(self):
        with patch.dict(os.environ, {}, clear=True):
            response = self.client.get("/api/config", base_url=self.base_url)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["providers"], {"openai": False, "gemini": False})
        self.assertIsNone(response.json["unlockedProvider"])
        self.assertEqual(response.headers["Cache-Control"], "no-store, private, max-age=0")

    def test_mutating_requests_require_csrf_and_same_origin(self):
        _home, csrf = self.home()
        missing = self.client.post(
            "/api/unlock",
            base_url=self.base_url,
            json={"provider": "openai", "accessCode": "anything"},
            headers={"Origin": "https://minddeck.test", "User-Agent": self.user_agent},
        )
        cross_site = self.post(
            "/api/unlock",
            {"provider": "openai", "accessCode": "anything"},
            csrf,
            origin="https://attacker.example",
        )

        self.assertEqual(missing.status_code, 403)
        self.assertEqual(cross_site.status_code, 403)

    def test_browser_secrets_are_rejected_by_generation_endpoint(self):
        _home, csrf = self.home()
        response = self.post(
            "/api/generate",
            {
                "provider": "openai",
                "text": "A long enough set of notes for security testing.",
                "apiKey": "must-not-be-accepted",
            },
            csrf,
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("Secrets are not accepted", response.json["error"])

    def test_scrypt_unlock_creates_short_lived_httponly_session(self):
        environment = self.secure_environment()
        with patch.dict(os.environ, environment, clear=True):
            _home, csrf = self.home()
            denied = self.post(
                "/api/unlock",
                {"provider": "openai", "accessCode": "wrong-code"},
                csrf,
            )
            allowed = self.post(
                "/api/unlock",
                {"provider": "openai", "accessCode": "a-very-strong-test-code"},
                csrf,
            )

        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)
        session_cookie = allowed.headers["Set-Cookie"]
        self.assertIn("__Host-minddeck_ai=", session_cookie)
        self.assertIn("HttpOnly", session_cookie)
        self.assertIn("Secure", session_cookie)
        self.assertIn("SameSite=Strict", session_cookie)
        self.assertLessEqual(allowed.json["expiresIn"], 15 * 60)

    def test_generation_requires_valid_session_and_keeps_key_server_side(self):
        captured = {}

        def fake_post(url, payload, headers):
            captured.update(url=url, payload=payload, headers=headers)
            return {
                "choices": [
                    {"message": {"content": '{"cards":[{"front":"Q","back":"A"}]}'}}
                ]
            }

        environment = self.secure_environment()
        with patch.dict(os.environ, environment, clear=True), patch.object(
            minddeck, "post_json", side_effect=fake_post
        ):
            _home, csrf = self.home()
            before_unlock = self.post(
                "/api/generate",
                {"provider": "openai", "text": "Photosynthesis converts light into chemical energy."},
                csrf,
            )
            unlocked = self.post(
                "/api/unlock",
                {"provider": "openai", "accessCode": "a-very-strong-test-code"},
                csrf,
            )
            generated = self.post(
                "/api/generate",
                {"provider": "openai", "text": "Photosynthesis converts light into chemical energy."},
                csrf,
            )

        self.assertEqual(before_unlock.status_code, 401)
        self.assertEqual(unlocked.status_code, 200)
        self.assertEqual(generated.status_code, 200)
        self.assertEqual(generated.json["cards"], [{"front": "Q", "back": "A"}])
        self.assertEqual(captured["headers"]["Authorization"], "Bearer server-only-test-key")
        self.assertNotIn("server-only-test-key", generated.get_data(as_text=True))
        self.assertFalse(captured["payload"]["store"])

    def test_lock_endpoint_revokes_browser_session(self):
        environment = self.secure_environment()
        with patch.dict(os.environ, environment, clear=True):
            _home, csrf = self.home()
            self.post(
                "/api/unlock",
                {"provider": "openai", "accessCode": "a-very-strong-test-code"},
                csrf,
            )
            locked = self.post("/api/lock", {}, csrf)
            generated = self.post(
                "/api/generate",
                {"provider": "openai", "text": "A long enough set of notes after locking."},
                csrf,
            )

        self.assertEqual(locked.status_code, 200)
        self.assertIn("Max-Age=0", locked.headers["Set-Cookie"])
        self.assertEqual(generated.status_code, 401)

    def test_unlock_brute_force_limit(self):
        environment = self.secure_environment()
        with patch.dict(os.environ, environment, clear=True):
            _home, csrf = self.home()
            responses = [
                self.post("/api/unlock", {"provider": "openai", "accessCode": ""}, csrf)
                for _ in range(6)
            ]

        self.assertEqual([response.status_code for response in responses[:5]], [401] * 5)
        self.assertEqual(responses[5].status_code, 429)
        self.assertIn("Retry-After", responses[5].headers)


if __name__ == "__main__":
    unittest.main()
