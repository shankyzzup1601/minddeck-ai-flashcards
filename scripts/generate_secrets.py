"""Generate MindDeck access credentials without storing the plaintext code."""

import getpass
import secrets

from werkzeug.security import generate_password_hash


def main() -> None:
    code = getpass.getpass("Choose an AI access code (20+ characters): ")
    confirmation = getpass.getpass("Confirm the access code: ")
    if code != confirmation:
        raise SystemExit("The access codes did not match.")
    if len(code) < 20:
        raise SystemExit("Use at least 20 characters.")

    print("\nAdd these values to the hosting provider's encrypted environment settings:")
    print(f"AI_ACCESS_CODE_HASH={generate_password_hash(code, method='scrypt')}")
    print(f"AI_SESSION_SECRET={secrets.token_urlsafe(48)}")
    print(f"OAUTH_SESSION_SECRET={secrets.token_urlsafe(48)}")
    print("\nDo not save or commit the plaintext access code.")


if __name__ == "__main__":
    main()
