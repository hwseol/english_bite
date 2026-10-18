# EnglishBite - how changes reach testers

Two copies of the API run on the same server (no extra cost):

| | production | dev |
|---|---|---|
| address | `https://184-193-203-68.sslip.io` | `https://dev.184-193-203-68.sslip.io` |
| git branch | `main` | `dev` |
| code / data | `~/english_bite` | `~/english_bite_dev`, `~/english_bite_dev_data` |
| used by | release app (Play) | debug app (Android Studio / `assembleDebug`) |
| accounts / videos | real | separate copy |

## Workflow for any server or app change

1. Work on branch `dev`. Push it, then `bash ~/english_bite/backend/deploy/deploy.sh dev` on the server.
2. Install a **debug** build (talks to dev) and try it, including with an older release build if
   the change touches the API.
3. Merge `dev` into `main`, push, then `bash ~/english_bite/backend/deploy/deploy.sh prod`.

## Compatibility rule (older app versions stay installed on people's phones)

- **Only add** to the API: new endpoints, new optional fields. Never rename/remove an endpoint
  or field, never make a new field required, never change what an existing one means.
- If a breaking change is truly unavoidable: ship the new app first, wait for people to update,
  then raise `min_version_code` in `docs/app-config.json` - builds below it show an "update
  required" screen instead of failing. Builds from before versionCode 6 have no such screen and
  keep talking to the old API shape, so keep it working for them for as long as they exist.
- The server address lives in `docs/app-config.json` (`api_base`) - moving to a new host or a
  real domain is editing that file (GitHub Pages, a few minutes), not an app release. Keep the
  old host answering for builds older than versionCode 6, which have it built in.

## Looking at things on the server

```
cd ~/english_bite/backend/translate
.venv/bin/python view_crashes.py              # crash reports, grouped      (prod)
.venv/bin/python view_crashes.py --full 3     # last 3 full stack traces
EB_DATA_DIR=~/english_bite_dev_data .venv/bin/python view_crashes.py       # same, for dev
.venv/bin/python reset_password.py someone@example.com                    # forgotten password
```

## Sign-up verification mails and Google sign-in

New accounts must prove their email: the app asks `POST /auth/email/send` for a 6-digit code (valid 15 min,
5 tries, one per minute per address, 200 mails/day for the whole server) and sends it with the sign-up
(`code`). Password reset uses the same codes (`/auth/reset/confirm`). Google sign-in is `POST /auth/google`
(the phone's ID token, checked with Google's tokeninfo). `GET /auth/config` tells the app what is switched on.

Server settings (systemd drop-in `mail.conf`, root-only, per service):
`EB_SMTP_HOST/PORT/USER/PASSWORD/FROM` and `EB_GOOGLE_CLIENT_ID`. Set both with
`backend\deploy\set_mail.ps1 -Key <key.pem> -Server <user@host>` (reuses Bujirun's Gmail account inside the
server; `-Manual` to type another). `EB_MAIL_DEBUG_LOG=<file>` (dev only) appends the mails to a file
instead of sending them. Order of a release: push -> `deploy.sh dev` -> `set_mail.ps1` -> try it in the dev
app -> `deploy.sh prod` (the sign-up screen of an app older than versionCode 8 is told to update).

Google Cloud Console (the same project as the web client ID in `app/build.gradle.kts`): create an
**Android** OAuth client for package `com.mhmh2.englishbite` once per signing key SHA-1 - the upload key
(`keytool -list -v` on the release keystore), the debug key, and the **Play app signing key** (Play Console
-> Release -> Setup -> App signing). Without a matching client Google answers "[28444] Developer console is
not set up correctly" and the button reports it.
