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
