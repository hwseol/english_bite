"""Operator tool: reset a user's password when they've forgotten it (run on the server).

    python reset_password.py someone@example.com

Prints a random temporary password and signs the account out everywhere. Tell the person the
temporary password over email; they log in with it and then change it in the app
(account icon -> 비밀번호 변경). Nothing here sends email - there's no mail service wired up.
"""

import secrets
import sys

import auth_db


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    temp = secrets.token_urlsafe(9)  # 12 chars, comfortably above the 8-char minimum
    if not auth_db.admin_reset_password(sys.argv[1], temp):
        print("No account with that email.")
        return 1
    print(f"Temporary password for {sys.argv[1].strip().lower()}: {temp}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
