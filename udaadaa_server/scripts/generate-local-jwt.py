#!/usr/bin/env python3
"""Generate an HS256 JWT for the local Spring profile only."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import time
import uuid


LOCAL_ISSUER = os.getenv("SUPABASE_JWT_ISSUER", "https://local.udaadaa.test/auth/v1")
LOCAL_AUDIENCE = os.getenv("SUPABASE_JWT_AUDIENCE", "authenticated")
LOCAL_SECRET = os.getenv(
    "SUPABASE_JWT_SECRET",
    "local-only-jwt-secret-change-before-shared-use",
).encode()


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a local Udaadaa bearer token")
    parser.add_argument(
        "--user-id",
        default="00000000-0000-0000-0000-000000000001",
        type=uuid.UUID,
        help="JWT subject UUID",
    )
    args = parser.parse_args()

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": LOCAL_ISSUER,
        "sub": str(args.user_id),
        "aud": LOCAL_AUDIENCE,
        "role": "authenticated",
        "iat": now,
        "exp": now + 3600,
    }
    unsigned = ".".join(
        [
            encode(json.dumps(header, separators=(",", ":")).encode()),
            encode(json.dumps(payload, separators=(",", ":")).encode()),
        ]
    )
    signature = hmac.new(LOCAL_SECRET, unsigned.encode(), hashlib.sha256).digest()
    print(f"{unsigned}.{encode(signature)}")


if __name__ == "__main__":
    main()
