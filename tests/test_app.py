import base64
import hashlib
import json
import os
import re
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

from werkzeug.security import generate_password_hash

import app as minddeck


class MindDeckSecurityTests(unittest.TestCase):
    def test_chrome_pull_to_refresh_is_disabled(self):
        template = Path("templates/index.html").read_text(encoding="utf-8")
        self.assertGreaterEqual(template.count("overscroll-behavior-y:none"), 2)

    def test_reinstall_requires_study_profile_for_every_account(self):
        template = Path("templates/index.html").read_text(encoding="utf-8")
        self.assertIn('key.startsWith("minddeck-study-profile-complete-v1:")', template)
        self.assertIn("localStorage.removeItem(key)", template)

    def test_service_worker_update_never_forces_an_app_reload(self):
        script = Path("static/app.js").read_text(encoding="utf-8")
        self.assertNotIn('addEventListener("controllerchange"', script)
        self.assertNotIn("window.location.reload()", script)

    def test_fresh_install_flag_is_consumed_once_in_the_browser(self):
        template = Path("templates/index.html").read_text(encoding="utf-8")
        self.assertIn('cleanUrl.searchParams.delete("fresh-install")', template)
        self.assertIn("window.history.replaceState", template)

    def test_mobile_account_name_is_stable_while_auth_state_loads(self):
        script = Path("static/app.js").read_text(encoding="utf-8")
        self.assertIn('ACCOUNT_NAME_STORE = "minddeck-last-account-name-v1"', script)
        self.assertIn("if (!authStateResolved)", script)
        self.assertIn("localStorage.setItem(ACCOUNT_NAME_STORE, confirmedName)", script)
        self.assertIn("localStorage.removeItem(ACCOUNT_NAME_STORE)", script)

    def test_service_worker_does_not_cache_auth_or_fresh_install_navigation(self):
        worker = Path("static/sw.js").read_text(encoding="utf-8")
        self.assertIn('url.pathname === "/" && !url.search', worker)
        self.assertIn("response.ok && isCleanHome", worker)

    def test_fresh_android_install_renders_home_and_clears_session(self):
        self.client.set_cookie("__Host-minddeck_access", "a" * 128, domain="minddeck.test")
        self.client.set_cookie("__Host-minddeck_refresh", "r" * 64, domain="minddeck.test")
        response = self.client.get(
            "/?fresh-install=android-v2",
            base_url=self.base_url,
            headers={
                "User-Agent": "Mozilla/5.0 (Linux; Android 15) MindDeck",
                "X-Forwarded-Proto": "https",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn("MindDeck AI Flashcards", response.get_data(as_text=True))
        cookies = "\n".join(response.headers.getlist("Set-Cookie"))
        self.assertGreaterEqual(cookies.count("Max-Age=0"), 3)

    def test_clean_android_download_redirects_to_verified_release(self):
        response = self.client.get(
            "/download/MindDeck.apk",
            base_url=self.base_url,
            headers={"X-Forwarded-Proto": "https"},
        )
        self.assertEqual(response.status_code, 302)
        self.assertEqual(
            response.headers["Location"],
            "https://github.com/shankyzzup1601/minddeck-ai-flashcards/releases/download/minddeck-android-v1.2.0/MindDeck.apk",
        )

    def test_android_asset_links_verify_published_apk(self):
        response = self.client.get(
            "/.well-known/assetlinks.json",
            base_url=self.base_url,
            headers={"X-Forwarded-Proto": "https"},
        )
        self.assertEqual(response.status_code, 200)
        statement = response.json[0]
        self.assertEqual(
            statement["relation"], ["delegate_permission/common.handle_all_urls"]
        )
        self.assertEqual(statement["target"]["package_name"], "com.minddeck.app")
        self.assertIn(
            "72:97:3B:C1:B0:FF:5B:24:99:B1:11:85:C6:0A:FD:64:0A:45:35:39:34:35:6F:F4:C6:AC:7D:9B:95:F3:FA:F7",
            statement["target"]["sha256_cert_fingerprints"],
        )
        self.assertIn(
            "A2:56:F6:5F:B9:19:6B:FF:55:EB:76:52:B8:09:A6:59:3A:2D:4C:88:AF:0A:5B:2B:61:7E:31:C9:4F:53:19:21",
            statement["target"]["sha256_cert_fingerprints"],
        )
        self.assertIn(
            "D0:EB:88:22:AC:B3:23:82:3F:40:CB:6D:01:86:CE:45:86:AA:80:17:9E:0B:AF:B9:D7:A6:5A:A8:E7:A5:6B:B5",
            statement["target"]["sha256_cert_fingerprints"],
        )

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

    @staticmethod
    def gateway_environment():
        return {"VERCEL_OIDC_TOKEN": "oidc-" + ("t" * 64)}

    @classmethod
    def google_environment(cls):
        return {
            **cls.auth_environment(),
            "OAUTH_SESSION_SECRET": "o" * 48,
            "PUBLIC_APP_URL": cls.base_url,
        }

    def test_home_has_no_browser_api_key_or_third_party_script(self):
        response, _csrf = self.home()
        page = response.get_data(as_text=True)

        self.assertEqual(response.status_code, 200)
        self.assertNotIn('id="apiKey"', page)
        self.assertNotIn('id="accessCode"', page)
        self.assertIn('id="aiStatusBadge"', page)
        self.assertIn("MindDeck AI", page)
        self.assertNotIn("Owner access code", page)
        self.assertNotIn("Unlock selected AI", page)
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
        self.assertIn('id="googleSignIn"', page)
        self.assertIn('id="oauthSecurity"', page)
        self.assertIn('placeholder="Enter your password"', page)
        self.assertIn('data-theme="aurora"', page)
        self.assertIn('data-theme="rose"', page)
        self.assertNotIn("unpkg.com", page)
        self.assertNotIn("cdnjs.cloudflare.com", page)
        self.assertNotIn("accounts.google.com/gsi", page)
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

    def test_returning_account_cookie_skips_first_run_splash(self):
        self.client.set_cookie(
            "__Host-minddeck_refresh",
            "returning-user-refresh-token",
            domain="minddeck.test",
            secure=True,
            path="/",
        )

        response, _csrf = self.home()
        page = response.get_data(as_text=True)

        self.assertEqual(response.status_code, 200)
        self.assertIn('<html lang="en" class="splash-seen">', page)
        self.assertNotIn("returning-user-refresh-token", page)

    def test_online_providers_fail_closed_without_all_secrets(self):
        with patch.dict(os.environ, {}, clear=True):
            response = self.client.get("/api/config", base_url=self.base_url)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json["providers"],
            {"minddeck": False, "openai": False, "gemini": False},
        )
        self.assertEqual(response.json["minddeckAi"], {"ready": False, "requiresSignIn": True})
        self.assertIsNone(response.json["unlockedProvider"])
        self.assertEqual(response.headers["Cache-Control"], "no-store, private, max-age=0")

    def test_minddeck_ai_reports_ready_with_vercel_identity(self):
        with patch.dict(os.environ, self.gateway_environment(), clear=True):
            response = self.client.get("/api/config", base_url=self.base_url)

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json["providers"]["minddeck"])
        self.assertTrue(response.json["minddeckAi"]["ready"])

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
        self.assertFalse(config.json["googleEnabled"])
        self.assertIsNone(config.json["user"])
        self.assertEqual(signup.status_code, 503)

    def test_google_auth_config_checks_provider_and_signing_secret(self):
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck,
            "supabase_json",
            return_value={"external": {"google": True, "email": True}},
        ) as upstream:
            config = self.client.get(
                "/api/auth/config",
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )

        self.assertEqual(config.status_code, 200)
        self.assertTrue(config.json["enabled"])
        self.assertTrue(config.json["googleEnabled"])
        self.assertEqual(upstream.call_args.args[:2], ("GET", "/auth/v1/settings"))

        without_secret = self.auth_environment()
        with patch.dict(os.environ, without_secret, clear=True), patch.object(
            minddeck, "supabase_json"
        ) as unavailable_upstream:
            unavailable = self.client.get(
                "/api/auth/config",
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )

        self.assertFalse(unavailable.json["googleEnabled"])
        unavailable_upstream.assert_not_called()

    def test_google_avatar_url_is_restricted_to_googleusercontent(self):
        trusted = "https://lh3.googleusercontent.com/a/example-photo=s96-c"
        self.assertEqual(
            minddeck.google_avatar_url({"user_metadata": {"avatar_url": trusted}}),
            trusted,
        )
        self.assertEqual(
            minddeck.google_avatar_url(
                {"user_metadata": {"avatar_url": "https://attacker.example/avatar.png"}}
            ),
            "",
        )
        self.assertEqual(
            minddeck.google_avatar_url(
                {"user_metadata": {"picture": "http://lh3.googleusercontent.com/avatar.png"}}
            ),
            "",
        )

    def test_google_start_uses_pkce_and_signed_callback_transaction(self):
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "google_auth_ready", return_value=True
        ):
            _home, csrf = self.home()
            response = self.post("/api/auth/google/start", {}, csrf)

        self.assertEqual(response.status_code, 200)
        authorization_url = urlparse(response.json["authorizationUrl"])
        query = parse_qs(authorization_url.query)
        self.assertEqual(authorization_url.scheme, "https")
        self.assertEqual(authorization_url.netloc, "minddeck-test.supabase.co")
        self.assertEqual(authorization_url.path, "/auth/v1/authorize")
        self.assertEqual(query["provider"], ["google"])
        callback_url = urlparse(query["redirect_to"][0])
        callback_query = parse_qs(callback_url.query)
        self.assertEqual(callback_url.scheme, "https")
        self.assertEqual(callback_url.netloc, "minddeck.test")
        self.assertEqual(callback_url.path, "/api/auth/google/callback")
        self.assertEqual(callback_query["flow"], ["redirect"])
        self.assertRegex(callback_query["transaction"][0], r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$")
        self.assertEqual(query["code_challenge_method"], ["s256"])
        self.assertRegex(query["code_challenge"][0], r"^[A-Za-z0-9_-]{43}$")
        cookies = "\n".join(response.headers.getlist("Set-Cookie"))
        self.assertNotIn("__Host-minddeck_oauth=", cookies)
        self.assertNotIn("code_verifier", response.get_data(as_text=True))
        self.assertNotIn("access_token", response.get_data(as_text=True))

    def test_google_start_requires_csrf_same_origin_and_configuration(self):
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "google_auth_ready", return_value=True
        ):
            _home, csrf = self.home()
            no_csrf = self.client.post(
                "/api/auth/google/start",
                base_url=self.base_url,
                json={},
                headers={
                    "Origin": self.base_url,
                    "User-Agent": self.user_agent,
                    "X-Forwarded-Proto": "https",
                },
            )
            cross_site = self.post(
                "/api/auth/google/start", {}, csrf, origin="https://attacker.example"
            )
            downgraded_origin = self.post(
                "/api/auth/google/start", {}, csrf, origin="http://minddeck.test"
            )

        with patch.dict(os.environ, self.auth_environment(), clear=True):
            _home, csrf = self.home()
            unavailable = self.post("/api/auth/google/start", {}, csrf)

        self.assertEqual(no_csrf.status_code, 403)
        self.assertEqual(cross_site.status_code, 403)
        self.assertEqual(downgraded_origin.status_code, 400)
        self.assertEqual(unavailable.status_code, 503)

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

    def test_cloud_planner_tasks_are_sanitized(self):
        normalized = minddeck.normalize_cloud_deck(
            {
                "cards": [],
                "planner": [
                    {
                        "id": "plan_task_1",
                        "title": "  Revise electrostatics  ",
                        "subject": "Physics",
                        "date": "2026-08-29",
                        "time": "18:30",
                        "done": True,
                        "createdAt": 1_000,
                    },
                    {
                        "id": "plan_task_2",
                        "title": "Practise journal entries",
                        "subject": "Unknown",
                        "date": "2026-08-30",
                        "time": "99:99",
                        "done": "yes",
                        "createdAt": 2_000,
                    },
                    {"id": "bad", "title": "", "date": "invalid"},
                ],
                "updatedAt": 0,
            }
        )

        self.assertEqual(normalized["version"], 7)
        self.assertEqual(
            normalized["planner"],
            [
                {
                    "id": "plan_task_1",
                    "title": "Revise electrostatics",
                    "subject": "Physics",
                    "date": "2026-08-29",
                    "time": "18:30",
                    "done": True,
                    "createdAt": 1_000,
                },
                {
                    "id": "plan_task_2",
                    "title": "Practise journal entries",
                    "subject": "General",
                    "date": "2026-08-30",
                    "time": "",
                    "done": False,
                    "createdAt": 2_000,
                },
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
        self.assertEqual(normalized["version"], 7)
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
            "refresh_token": "r" * 12,
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

    def test_google_callback_exchanges_pkce_server_side_and_sets_auth_cookies(self):
        tokens = {
            "access_token": "a" * 128,
            "refresh_token": "r" * 12,
            "provider_token": "google-provider-token-must-not-leak",
            "expires_in": 3600,
        }
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "google_auth_ready", return_value=True
        ):
            _home, csrf = self.home()
            started = self.post("/api/auth/google/start", {}, csrf)

        challenge = parse_qs(urlparse(started.json["authorizationUrl"]).query)[
            "code_challenge"
        ][0]
        callback_url = parse_qs(urlparse(started.json["authorizationUrl"]).query)[
            "redirect_to"
        ][0]
        auth_code = "12345678-1234-1234-1234-123456789abc"
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "supabase_json", return_value=tokens
        ) as upstream:
            callback = self.client.get(
                f"{urlparse(callback_url).path}?{urlparse(callback_url).query}&code={auth_code}",
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )

        self.assertEqual(callback.status_code, 303)
        self.assertEqual(callback.headers["Location"], "/?auth=google-ok")
        self.assertEqual(upstream.call_args.args[0], "POST")
        self.assertEqual(upstream.call_args.args[1], "/auth/v1/token?grant_type=pkce")
        exchange = upstream.call_args.args[2]
        self.assertEqual(exchange["auth_code"], auth_code)
        derived_challenge = base64.urlsafe_b64encode(
            hashlib.sha256(exchange["code_verifier"].encode("ascii")).digest()
        ).rstrip(b"=").decode("ascii")
        self.assertEqual(derived_challenge, challenge)
        cookies = "\n".join(callback.headers.getlist("Set-Cookie"))
        self.assertIn("__Host-minddeck_access=", cookies)
        self.assertIn("__Host-minddeck_refresh=", cookies)
        self.assertIn("__Host-minddeck_oauth=;", cookies)
        self.assertIn("SameSite=Strict", cookies)
        self.assertIn("SameSite=Lax", cookies)
        self.assertIn("Max-Age=0", cookies)
        response_text = callback.get_data(as_text=True)
        self.assertNotIn(tokens["access_token"], response_text)
        self.assertNotIn(tokens["refresh_token"], response_text)
        self.assertNotIn(tokens["provider_token"], response_text)
        self.assertNotIn(tokens["provider_token"], callback.headers["Location"])

    def test_google_popup_callback_survives_android_browser_handoff(self):
        tokens = {
            "access_token": "a" * 128,
            "refresh_token": "r" * 12,
            "expires_in": 3600,
        }
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "google_auth_ready", return_value=True
        ):
            _home, csrf = self.home()
            started = self.post("/api/auth/google/start", {"popup": True}, csrf)

        redirect_to = parse_qs(urlparse(started.json["authorizationUrl"]).query)[
            "redirect_to"
        ][0]
        redirect_query = parse_qs(urlparse(redirect_to).query)
        self.assertEqual(redirect_query["flow"], ["popup"])
        self.assertIn("transaction", redirect_query)

        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "supabase_json", return_value=tokens
        ) as upstream:
            callback = self.client.get(
                f"{urlparse(redirect_to).path}?{urlparse(redirect_to).query}&code=12345678-1234-1234-1234-123456789abc",
                base_url=self.base_url,
                headers={
                    "User-Agent": "Chrome Android OAuth Handoff",
                    "X-Forwarded-Proto": "https",
                },
            )

        self.assertEqual(callback.status_code, 200)
        body = callback.get_data(as_text=True)
        self.assertIn('BroadcastChannel("minddeck-google-auth-v1")', body)
        self.assertIn('const result = "ok"', body)
        self.assertIn("window.close()", body)
        self.assertIn("Open MindDeck app", body)
        self.assertIn("https://minddeck.test/?auth=google-ok", body)
        self.assertNotIn("intent://", body)
        self.assertEqual(upstream.call_args.args[1], "/auth/v1/token?grant_type=pkce")
        cookies = "\n".join(callback.headers.getlist("Set-Cookie"))
        self.assertIn("__Host-minddeck_access=", cookies)
        self.assertIn("__Host-minddeck_refresh=", cookies)

    def test_google_callback_rejects_missing_tampered_and_expired_transactions(self):
        callback_path = "/api/auth/google/callback?code=12345678-1234-1234-1234-123456789abc"
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "supabase_json"
        ) as upstream:
            missing = self.client.get(
                callback_path,
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )
            tampered = self.client.get(
                callback_path + "&flow=redirect&transaction=tampered.transaction",
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )

        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "google_auth_ready", return_value=True
        ), patch.object(minddeck.time, "time", return_value=1_000):
            _home, csrf = self.home()
            started = self.post("/api/auth/google/start", {}, csrf)
            expired_callback = parse_qs(
                urlparse(started.json["authorizationUrl"]).query
            )["redirect_to"][0]
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "supabase_json"
        ) as expired_upstream, patch.object(minddeck.time, "time", return_value=2_000):
            expired = self.client.get(
                f"{urlparse(expired_callback).path}?{urlparse(expired_callback).query}&code=12345678-1234-1234-1234-123456789abc",
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )

        for response in (missing, tampered, expired):
            self.assertEqual(response.status_code, 303)
            self.assertEqual(response.headers["Location"], "/?auth=google-error")
            self.assertIn("Max-Age=0", "\n".join(response.headers.getlist("Set-Cookie")))
        upstream.assert_not_called()
        expired_upstream.assert_not_called()

    def test_google_callback_cancellation_clears_transaction_without_exchange(self):
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "google_auth_ready", return_value=True
        ):
            _home, csrf = self.home()
            started = self.post("/api/auth/google/start", {}, csrf)
            callback_url = parse_qs(
                urlparse(started.json["authorizationUrl"]).query
            )["redirect_to"][0]
        with patch.dict(os.environ, self.google_environment(), clear=True), patch.object(
            minddeck, "supabase_json"
        ) as upstream:
            response = self.client.get(
                f"{urlparse(callback_url).path}?{urlparse(callback_url).query}&error=access_denied",
                base_url=self.base_url,
                headers={"User-Agent": self.user_agent, "X-Forwarded-Proto": "https"},
            )

        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["Location"], "/?auth=google-error")
        self.assertIn("Max-Age=0", "\n".join(response.headers.getlist("Set-Cookie")))
        upstream.assert_not_called()

    def test_signin_accepts_an_existing_password_below_new_account_minimum(self):
        tokens = {
            "access_token": "a" * 128,
            "refresh_token": "r" * 12,
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
            "refresh_token": "r" * 12,
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

    def test_fresh_android_install_clears_existing_browser_session(self):
        self.client.set_cookie("__Host-minddeck_access", "a" * 128, domain="minddeck.test")
        self.client.set_cookie("__Host-minddeck_refresh", "r" * 64, domain="minddeck.test")
        response = self.client.get(
            "/api/auth/app/fresh-install",
            base_url=self.base_url,
            headers={
                "User-Agent": "MindDeck Android Trusted Web Activity",
                "Sec-Fetch-Site": "none",
                "X-Forwarded-Proto": "https",
            },
        )
        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["Location"], "/?fresh-install=complete")
        cookies = "\n".join(response.headers.getlist("Set-Cookie"))
        self.assertGreaterEqual(cookies.count("Max-Age=0"), 3)

    def test_fresh_install_reset_rejects_cross_site_browser_requests(self):
        response = self.client.get(
            "/api/auth/app/fresh-install",
            base_url=self.base_url,
            headers={
                "User-Agent": "Mozilla/5.0 (Linux; Android 15)",
                "Sec-Fetch-Site": "cross-site",
                "X-Forwarded-Proto": "https",
            },
        )
        self.assertEqual(response.status_code, 403)

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

    def test_syllabus_generation_needs_no_uploaded_notes(self):
        prompts = []

        def fake_syllabus_response(prompt, account_key, **_kwargs):
            prompts.append((prompt, account_key))
            return json.dumps(
                {
                    "cards": [
                        {
                            "front": f"Physics revision question {number}?",
                            "back": f"Accurate revision answer {number}.",
                            "subject": "Physics",
                        }
                        for number in range(1, 16)
                    ]
                }
            )

        with patch.object(minddeck, "minddeck_ai_ready", return_value=True), patch.object(
            minddeck, "current_cloud_user", return_value={"account_key": "a" * 24}
        ), patch.object(minddeck, "minddeck_ai_response", side_effect=fake_syllabus_response):
            _home, csrf = self.home()
            response = self.post(
                "/api/syllabus",
                {
                    "provider": "minddeck",
                    "classLevel": "Class 12",
                    "subject": "Physics",
                    "chapter": "Electric Charges and Fields",
                    "cardMode": "mixed",
                },
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json["cards"]), 15)
        self.assertIn("CBSE/NCERT Class 12 Physics", prompts[0][0])
        self.assertIn("Electric Charges and Fields", prompts[0][0])
        self.assertEqual(prompts[0][1], "a" * 24)

    def test_syllabus_generation_rejects_unknown_subjects(self):
        _home, csrf = self.home()
        response = self.post(
            "/api/syllabus",
            {
                "provider": "minddeck",
                "classLevel": "Class 12",
                "subject": "Management",
                "chapter": "Business Environment",
                "cardMode": "mixed",
            },
            csrf,
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("syllabus subject", response.json["error"])

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

    def test_minddeck_ai_requires_a_signed_in_account(self):
        with patch.dict(os.environ, self.gateway_environment(), clear=True), patch.object(
            minddeck, "current_cloud_user", return_value=None
        ), patch.object(minddeck, "post_json") as upstream:
            _home, csrf = self.home()
            response = self.post(
                "/api/generate",
                {
                    "provider": "minddeck",
                    "cardMode": "mixed",
                    "text": "Photosynthesis converts light energy into stored chemical energy.",
                },
                csrf,
            )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json["error"], "Sign in to use MindDeck AI.")
        upstream.assert_not_called()

    def test_minddeck_ai_generation_uses_gateway_identity_server_side(self):
        captured = {}

        def fake_post(url, payload, headers):
            captured.update(url=url, payload=payload, headers=headers)
            return {
                "choices": [
                    {"message": {"content": '{"cards":[{"front":"Q","back":"A"}]}'}}
                ]
            }

        account_key = "abcd" * 6
        user = {"id": "12345678-1234-1234-1234-123456789abc", "account_key": account_key}
        environment = {**self.gateway_environment(), "AI_GATEWAY_MODEL": "google/gemini-3.6-flash"}
        with patch.dict(os.environ, environment, clear=True), patch.object(
            minddeck, "current_cloud_user", return_value=user
        ), patch.object(minddeck, "post_json", side_effect=fake_post):
            _home, csrf = self.home()
            response = self.post(
                "/api/generate",
                {
                    "provider": "minddeck",
                    "cardMode": "mixed",
                    "text": "Photosynthesis converts light energy into stored chemical energy.",
                },
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["cards"], [{"front": "Q", "back": "A"}])
        self.assertEqual(captured["url"], minddeck.AI_GATEWAY_URL)
        self.assertEqual(captured["payload"]["model"], "google/gemini-3.6-flash")
        self.assertEqual(captured["payload"]["providerOptions"]["gateway"]["user"], account_key)
        self.assertFalse(captured["payload"]["store"])
        self.assertEqual(
            captured["headers"]["Authorization"],
            "Bearer " + self.gateway_environment()["VERCEL_OIDC_TOKEN"],
        )
        self.assertNotIn(self.gateway_environment()["VERCEL_OIDC_TOKEN"], response.get_data(as_text=True))

    def test_minddeck_ai_vision_sends_the_image_to_the_gateway(self):
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
        user = {"id": "12345678-1234-1234-1234-123456789abc", "account_key": "cafe" * 6}
        with patch.dict(os.environ, self.gateway_environment(), clear=True), patch.object(
            minddeck, "current_cloud_user", return_value=user
        ), patch.object(minddeck, "post_json", side_effect=fake_post):
            _home, csrf = self.home()
            response = self.post(
                "/api/vision",
                {"provider": "minddeck", "cardMode": "mixed", "imageData": image_data},
                csrf,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json["cards"], [{"front": "Image Q", "back": "Image A"}])
        content = captured["payload"]["messages"][0]["content"]
        self.assertEqual(content[1]["type"], "image_url")
        self.assertTrue(content[1]["image_url"]["url"].startswith("data:image/jpeg;base64,"))

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
