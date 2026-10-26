"""Records the admin live map from the running one-command demo as an animated GIF.

CI runs it on a manual run of the CI workflow (ci.yml), once the demo is up and the simulator's
trips are being booked:

    ADMIN_PHONE=... ADMIN_PASSWORD=... python .github/scripts/record_live_map.py live-map.gif
"""

import io
import os
import sys
import time

from PIL import Image
from playwright.sync_api import sync_playwright

URL = os.environ.get("LIVE_MAP_URL", "http://localhost:8080/admin/live-map.html")
FRAMES = 40
INTERVAL_SECONDS = 0.5
WIDTH = 800


def record(out: str) -> None:
    frames = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 720})
        page.goto(URL)
        page.fill("#phone", os.environ["ADMIN_PHONE"])
        page.fill("#password", os.environ["ADMIN_PASSWORD"])
        page.click("button[type=submit]")
        page.wait_for_selector("#login", state="hidden", timeout=15_000)
        # The first snapshot fills the counters; the map tiles get a few seconds more.
        page.wait_for_function(
            "document.getElementById('k-online').textContent !== '-'", timeout=60_000
        )
        time.sleep(5)
        for _ in range(FRAMES):
            shot = Image.open(io.BytesIO(page.screenshot())).convert("RGB")
            height = WIDTH * shot.height // shot.width
            frames.append(shot.resize((WIDTH, height), Image.Resampling.LANCZOS).quantize(128))
            time.sleep(INTERVAL_SECONDS)
        browser.close()

    frames[0].save(
        out,
        save_all=True,
        append_images=frames[1:],
        duration=int(INTERVAL_SECONDS * 1000),
        loop=0,
        optimize=True,
    )
    print(f"{out}: {len(frames)} frames, {os.path.getsize(out) // 1024} KiB")


if __name__ == "__main__":
    record(sys.argv[1])
