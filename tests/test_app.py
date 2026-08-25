import base64
import os
import re
import unittest
from pathlib import Path
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
        return self.request_json("POST", path, payload, csrf, origin)

    def request_json(self, method, path, payload, csrf, origin="https://minddeck.test"):
        return self.client.open(
            method=method,
            path=path,
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

    @staticmethod
    def auth_environment():
        return {
            "SUPABASE_URL": "https://minddeck-test.supabase.co",
            "SUPABASE_PUBLISHABLE_KEY": "publishable-test-key-" + "p" * 32,
        }

    def test_home_has_no_browser_api_key_or_third_party_script(self):
        response, _csrf = self.home()
        page = response.get_data(as_text=True)

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('id="apiKey"', page)
        self.assertIn('id="accessCode"', page)
        self.assertIn('id="account"', page)
        self.assertIn('id="timerWidget"', page)
        self.assertIn('id="weekBars"', page)
        self.assertIn('id="memoryWidget"', page)
        self.assertIn('id="memoryArc"', page)
        self.assertIn('id="forecastWidget"', page)
        self.assertIn('id="quickActions"', page)
        self.assertIn('id="smartStudyLab"', page)
        self.assertIn('id="reviewHeatmap"', page)
        self.assertIn('id="feynmanRecord"', page)
        self.assertIn('id="photoFile"', page)
        self.assertIn('id="cardMode"', page)
        self.assertIn('id="leechModal"', page)
        self.assertIn('id="matchModal"', page)
        self.assertIn('id="exchangeModal"', page)
        self.assertIn('id="examModal"', page)
        self.assertIn('id="examTemplate"', page)
        self.assertIn('id="formulaCram"', page)
        self.assertIn('id="mistakeModal"', page)
        self.assertIn('id="examProgressive"', page)
        self.assertIn('value="ncert"', page)
        self.assertIn('/static/manifest.webmanifest', page)
        self.assertIn('id="themeToggle"', page)
        self.assertIn('id="togglePassword"', page)
        self.assertIn('placeholder="Enter your password"', page)
        self.assertIn('data-theme="aurora"', page)
        self.assertIn('data-theme="rose"', page)
        self.assertNotIn("unpkg.com", page)
        self.assertNotIn("cdnjs.cloudflare.com", page)
        self.assertNotIn("SUPABASE_PUBLISHABLE_KEY", page)
        self.assertIn('/static/app.js', page)
        self.assertEqual(response.headers["X-Frame-Options"], "DENY")
        self.assertEqual(response.headers["Cross-Origin-Embedder-Policy"], "require-corp")
        self.assertIn("script-src 'self' 'nonce-", response.headers["Content-Security-Policy"])
        self.assertIn("style-src-attr 'none'", response.headers["Content-Security-Policy"])
        self.assertIn("require-trusted-types-for 'script'", response.headers["Content-Security-Policy"])
        self.assertIn("media-src 'self' blob:", response.headers["Content-Security-Policy"])
        self.assertIn("camera=(self)", response.headers["Permissions-Policy"])
        self.assertIn("microphone=(self)", response.headers["Permissions-Policy"])
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

    def test_cloud_accounts_fail_closed_without_configuration(self):
        with patch.dict(os.environ, {}, clear=True):
            _home, csrf = self.home()
            config = self.client.get("/api/auth/config", base_url=self.base_url)
            signup = self.post(
                "/api/auth/signup",
                {"email": "student@example.com", "password": "a-strong-password"},
                csrf,
            )

        self.assertEqual(config.status_code, 200)
        self.assertFalse(config.json["enabled"])
        self.assertIsNone(config.json["user"])
        self.assertEqual(signup.status_code, 503)

    def test_cloud_schema_forces_user_isolation(self):
        schema = (Path(__file__).parents[1] / "supabase" / "schema.sql").read_text(
            encoding="utf-8"
        ).lower()

        self.assertIn("force row level security", schema)
        self.assertIn("revoke all on table public.minddeck_decks from anon", schema)
        self.assertIn("for select", schema)
        self.assertIn("for insert", schema)
        self.assertIn("for update", schema)
        self.assertIn("for delete", schema)
        self.assertIn("auth.uid()", schema)
        self.assertNotIn("service_role", schema)

    def test_cloud_study_stats_are_sanitized(self):
        normalized = minddeck.normalize_cloud_deck(
            {
                "cards": [],
                "index": 0,
                "reviewed": [],
                "updatedAt": 0,
                "study": {
                    "totalSeconds": 5_400,
                    "sessions": 3,
                    "dailyGoalMinutes": 50,
                    "dailyFocus": [
                        {"date": "2026-08-24", "seconds": 1_500},
                        {"date": "2026-08-25", "seconds": 3_900},
                        {"date": "2026-99-99", "seconds": 500},
                    ],
                    "dailyReviews": [
                        {"date": "2026-08-24", "count": 14},
                        {"date": "2026-08-25", "count": 6},
                        {"date": "invalid", "count": 100},
                    ],
                },
            }
        )

        self.assertEqual(normalized["study"]["totalSeconds"], 5_400)
        self.assertEqual(normalized["study"]["sessions"], 3)
        self.assertEqual(normalized["study"]["dailyGoalMinutes"], 50)
        self.assertEqual(
            normalized["study"]["dailyFocus"],
            [
                {"date": "2026-08-24", "seconds": 1_500},
                {"date": "2026-08-25", "seconds": 3_900},
            ],
        )
        self.assertEqual(
            normalized["study"]["dailyReviews"],
            [
                {"date": "2026-08-24", "count": 14},
                {"date": "2026-08-25", "count": 6},
            ],
        )

    def test_cloud_deck_preserves_smart_learning_state(self):
        normalized = minddeck.normalize_cloud_deck(
            {
                "cards": [
                    {
                        "id": "card_smart_1",
                        "front": "Plants use _____",
                        "back": "Plants use chlorophyll.",
                        "type": "cloze",
                        "clozeText": "Plants use {{c1::chlorophyll}}.",
                        "lapseStreak": 4,
                        "leech": True,
                        "lastScore": 1,
                        "hints": ["Think pigment", "It absorbs light", "Starts with chl"],
                    }
                ],
                "updatedAt": 1,
            }
        )

        card = normalized["cards"][0]
        self.assertEqual(card["type"], "cloze")
        self.assertEqual(card["clozeText"], "Plants use {{c1::chlorophyll}}.")
        self.assertEqual(card["lapseStreak"], 4)
        self.assertTrue(card["leech"])
        self.assertEqual(len(card["hints"]), 3)

    def test_cloud_deck_preserves_sanitized_exam_card_state(self):
        normalized = minddeck.normalize_cloud_deck(
            {
                "cards": [
                    {
                        "id": "formula_card_1",
                        "front": "$F = ma$",
                        "back": "newton · [M L T^-2]",
                        "template": "formula",
                        "subject": "Physics",
                        "examTags": ["JEE Main 2024", "JEE Main 2024", "Formula"],
                        "trap": True,
                        "mistake": True,
                        "mistakeAt": "2026-08-25T10:30:00+00:00",
                        "priority": "high",
                        "sections": [
                            {"label": "SI unit", "value": "newton (N)"},
                            {"label": "Dimensional formula", "value": "[M L T^-2]"},
                            {"label": 42, "value": "ignored"},
                        ],
                    }
                ],
                "updatedAt": 1,
            }
        )

        card = normalized["cards"][0]
        self.assertEqual(normalized["version"], 5)
        self.assertEqual(card["template"], "formula")
        self.assertEqual(card["subject"], "Physics")
        self.assertEqual(card["examTags"], ["JEE Main 2024", "Formula"])
        self.assertTrue(card["trap"])
        self.assertTrue(card["mistake"])
        self.assertEqual(card["priority"], "high")
        self.assertEqual(len(card["sections"]), 2)

    def test_signin_uses_httponly_auth_cookies(self):
        tokens = {
            "access_token": "a" * 128,
            "refresh_token": "r" * 64,
            "expires_in": 3600,
        }
        with patch.dict(os.environ, self.auth_environment(), clear=True), patch.object(
            minddeck, "supabase_json", return_value=tokens
        ) as upstream:
            _home, csrf = self.home()
            response = self.post(
                "/api/auth/signin",
                {"email": "student@example.com", "password": "a-strong-password"},
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        cookies = "\n".join(response.headers.getlist("Set-Cookie"))
        self.assertIn("__Host-minddeck_access=", cookies)
        self.assertIn("__Host-minddeck_refresh=", cookies)
        self.assertIn("HttpOnly", cookies)
        self.assertIn("Secure", cookies)
        self.assertIn("SameSite=Strict", cookies)
        self.assertNotIn(tokens["access_token"], response.get_data(as_text=True))
        self.assertEqual(upstream.call_args.args[1], "/auth/v1/token?grant_type=password")

    def test_signin_accepts_an_existing_password_below_new_account_minimum(self):
        tokens = {
            "access_token": "a" * 128,
            "refresh_token": "r" * 64,
            "expires_in": 3600,
        }
        with patch.dict(os.environ, self.auth_environment(), clear=True), patch.object(
            minddeck, "supabase_json", return_value=tokens
        ) as upstream:
            _home, csrf = self.home()
            response = self.post(
                "/api/auth/signin",
                {"email": "student@example.com", "password": "old-pass"},
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(upstream.call_args.args[2]["password"], "old-pass")

    def test_signup_keeps_strong_new_password_requirement(self):
        with patch.dict(os.environ, self.auth_environment(), clear=True), patch.object(
            minddeck, "supabase_json"
        ) as upstream:
            _home, csrf = self.home()
            response = self.post(
                "/api/auth/signup",
                {"email": "student@example.com", "password": "old-pass"},
                csrf,
            )

        self.assertEqual(response.status_code, 400)
        self.assertIn("at least 12", response.json["error"])
        upstream.assert_not_called()

    def test_unconfirmed_email_gets_actionable_signin_message(self):
        with patch.dict(os.environ, self.auth_environment(), clear=True), patch.object(
            minddeck,
            "supabase_json",
            side_effect=minddeck.SupabaseError(400, {"code": "email_not_confirmed"}),
        ):
            _home, csrf = self.home()
            response = self.post(
                "/api/auth/signin",
                {"email": "student@example.com", "password": "correct-password"},
                csrf,
            )

        self.assertEqual(response.status_code, 403)
        self.assertIn("Confirm your email", response.json["error"])

    def test_non_object_auth_payload_is_rejected_without_server_error(self):
        with patch.dict(os.environ, self.auth_environment(), clear=True):
            _home, csrf = self.home()
            response = self.request_json("POST", "/api/auth/signin", ["invalid"], csrf)

        self.assertEqual(response.status_code, 401)

    def test_cloud_deck_requires_authentication(self):
        with patch.dict(os.environ, self.auth_environment(), clear=True):
            _home, csrf = self.home()
            get_response = self.client.get("/api/deck", base_url=self.base_url)
            put_response = self.request_json(
                "PUT",
                "/api/deck",
                {"deck": {"cards": [], "index": 0, "reviewed": [], "updatedAt": 0}},
                csrf,
            )

        self.assertEqual(get_response.status_code, 401)
        self.assertEqual(put_response.status_code, 401)

    def test_cloud_deck_is_saved_with_user_scoped_token(self):
        tokens = {
            "access_token": "a" * 128,
            "refresh_token": "r" * 64,
            "expires_in": 3600,
        }
        calls = []

        def fake_supabase(method, path, payload=None, **kwargs):
            calls.append((method, path, payload, kwargs))
            if path.endswith("grant_type=password"):
                return tokens
            if path == "/auth/v1/user":
                return {
                    "id": "12345678-1234-1234-1234-123456789abc",
                    "email": "student@example.com",
                }
            if path.startswith("/rest/v1/minddeck_decks"):
                return [{"updated_at": "2026-08-25T10:00:00+00:00"}]
            raise AssertionError(path)

        deck = {
            "cards": [
                {
                    "id": "card_1",
                    "front": "What is management?",
                    "back": "The process of achieving goals through people.",
                    "interval": 1,
                    "repetition": 1,
                    "easeFactor": 2.5,
                    "dueDate": "2026-08-26T10:00:00.000Z",
                    "reviews": 1,
                }
            ],
            "index": 0,
            "reviewed": ["card_1"],
            "updatedAt": 1_787_652_000_000,
        }
        with patch.dict(os.environ, self.auth_environment(), clear=True), patch.object(
            minddeck, "supabase_json", side_effect=fake_supabase
        ):
            _home, csrf = self.home()
            signed_in = self.post(
                "/api/auth/signin",
                {"email": "student@example.com", "password": "a-strong-password"},
                csrf,
            )
            saved = self.request_json("PUT", "/api/deck", {"deck": deck}, csrf)

        self.assertEqual(signed_in.status_code, 200)
        self.assertEqual(saved.status_code, 200)
        rest_call = next(call for call in calls if call[1].startswith("/rest/v1/minddeck_decks"))
        self.assertEqual(rest_call[2]["user_id"], "12345678-1234-1234-1234-123456789abc")
        self.assertEqual(rest_call[3]["bearer"], tokens["access_token"])
        self.assertIn("resolution=merge-duplicates", rest_call[3]["prefer"])

    def test_signout_clears_both_auth_cookies(self):
        with patch.dict(os.environ, self.auth_environment(), clear=True):
            _home, csrf = self.home()
            self.client.set_cookie("__Host-minddeck_access", "a" * 128, domain="minddeck.test")
            self.client.set_cookie("__Host-minddeck_refresh", "r" * 64, domain="minddeck.test")
            with patch.object(minddeck, "supabase_json", return_value={}):
                response = self.post("/api/auth/signout", {}, csrf)

        self.assertEqual(response.status_code, 200)
        cookies = "\n".join(response.headers.getlist("Set-Cookie"))
        self.assertGreaterEqual(cookies.count("Max-Age=0"), 2)

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

    def test_browser_secrets_are_rejected_by_vision_endpoint(self):
        _home, csrf = self.home()
        response = self.post(
            "/api/vision",
            {
                "provider": "openai",
                "cardMode": "mixed",
                "imageData": "data:image/jpeg;base64,invalid",
                "apiKey": "must-not-be-accepted",
            },
            csrf,
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("Secrets are not accepted", response.json["error"])

    def test_image_payload_requires_matching_magic_bytes(self):
        jpeg_bytes = b"\xff\xd8\xff" + (b"\x00" * 97)
        encoded = base64.b64encode(jpeg_bytes).decode("ascii")

        mime_type, clean = minddeck.parse_image_data(f"data:image/jpeg;base64,{encoded}")
        self.assertEqual(mime_type, "image/jpeg")
        self.assertEqual(clean, encoded)
        with self.assertRaises(ValueError):
            minddeck.parse_image_data(f"data:image/png;base64,{encoded}")

    def test_cloze_cards_keep_safe_structured_fields(self):
        cards = minddeck.parse_cards(
            '{"cards":[{"front":"Plants use _____","back":"Plants use chlorophyll",'
            '"type":"cloze","clozeText":"Plants use {{c1::chlorophyll}}."}]}'
        )

        self.assertEqual(cards[0]["type"], "cloze")
        self.assertEqual(cards[0]["clozeText"], "Plants use {{c1::chlorophyll}}.")

    def test_ai_exam_card_fields_are_schema_checked(self):
        cards = minddeck.parse_cards(
            '{"cards":[{"front":"$F = ma$","back":"newton",'
            '"template":"formula","subject":"Physics",'
            '"examTags":["JEE Main 2024","Formula"],"trap":true,'
            '"sections":[{"label":"SI unit","value":"newton (N)"}]}]}'
        )

        self.assertEqual(cards[0]["template"], "formula")
        self.assertEqual(cards[0]["subject"], "Physics")
        self.assertEqual(cards[0]["examTags"], ["JEE Main 2024", "Formula"])
        self.assertTrue(cards[0]["trap"])
        self.assertEqual(cards[0]["sections"], [{"label": "SI unit", "value": "newton (N)"}])

    def test_ncert_mode_requests_exact_cloze_and_trap_metadata(self):
        instruction = minddeck.card_mode_instruction("ncert")

        self.assertIn("line-by-line", instruction)
        self.assertIn("template 'ncert'", instruction)
        self.assertIn("boolean trap", instruction)

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

    def test_vision_generation_uses_locked_server_key(self):
        captured = {}

        def fake_post(url, payload, headers):
            captured.update(url=url, payload=payload, headers=headers)
            return {
                "choices": [
                    {"message": {"content": '{"cards":[{"front":"Image Q","back":"Image A"}]}'}}
                ]
            }

        image_bytes = b"\xff\xd8\xff" + (b"\x00" * 97)
        image_data = "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode("ascii")
        with patch.dict(os.environ, self.secure_environment(), clear=True), patch.object(
            minddeck, "post_json", side_effect=fake_post
        ):
            _home, csrf = self.home()
            self.post(
                "/api/unlock",
                {"provider": "openai", "accessCode": "a-very-strong-test-code"},
                csrf,
            )
            response = self.post(
                "/api/vision",
                {"provider": "openai", "cardMode": "mixed", "imageData": image_data},
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["cards"], [{"front": "Image Q", "back": "Image A"}])
        self.assertEqual(captured["headers"]["Authorization"], "Bearer server-only-test-key")
        self.assertIn("image_url", str(captured["payload"]))
        self.assertNotIn("server-only-test-key", response.get_data(as_text=True))

    def test_hint_generation_returns_three_non_browser_secrets(self):
        def fake_post(_url, _payload, _headers):
            return {
                "choices": [
                    {"message": {"content": '{"hints":["Concept","Relationship","Beginning"]}'}}
                ]
            }

        with patch.dict(os.environ, self.secure_environment(), clear=True), patch.object(
            minddeck, "post_json", side_effect=fake_post
        ):
            _home, csrf = self.home()
            self.post(
                "/api/unlock",
                {"provider": "openai", "accessCode": "a-very-strong-test-code"},
                csrf,
            )
            response = self.post(
                "/api/hint",
                {
                    "provider": "openai",
                    "front": "What is photosynthesis?",
                    "back": "Plants convert light into chemical energy.",
                },
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["hints"], ["Concept", "Relationship", "Beginning"])

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
