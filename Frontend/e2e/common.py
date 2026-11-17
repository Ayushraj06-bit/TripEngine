"""What every journey shares: the demo's address, two phones, checks, and screens that are audited.

The journeys drive the built web app in a real browser against the running one-command demo, over
its real REST API and WebSocket. run.sh starts them; see docs/development.md.
"""

import json
import os
import random
import sys
import traceback
from collections.abc import Iterator
from contextlib import contextmanager
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import Browser, BrowserContext, Page, Route, expect

BASE = os.environ.get("E2E_BASE", "http://localhost")
OUT = os.environ.get("E2E_OUT", "/out")
# In CI: OpenStreetMap's place search answered from the places below, and no map tiles - the
# run then depends on nothing outside the demo, and asks nothing of OpenStreetMap's servers.
OFFLINE_MAPS = os.environ.get("E2E_OFFLINE_MAPS") == "1"
# Baked into the journeys' image (Dockerfile).
AXE = "/opt/axe/axe.min.js"
AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "best-practice"]
PASSWORD = "correct-horse-7"

MG_ROAD = {"latitude": 12.9756, "longitude": 77.6069, "accuracy": 15}

PHONE = {
    "viewport": {"width": 390, "height": 844},
    "device_scale_factor": 2,
    "is_mobile": True,
    "has_touch": True,
    "locale": "en-IN",
    "timezone_id": "Asia/Kolkata",
    "reduced_motion": "reduce",
}

PLACES = [
    {
        "name": "Indiranagar",
        "display_name": "Indiranagar, Bengaluru East City Corporation, Bengaluru, Karnataka, 560038, India",
        "lat": "12.9783692",
        "lon": "77.6408356",
    },
    {
        "name": "Kempegowda International Airport",
        "display_name": "Kempegowda International Airport, Airport Road, Devanahalli, Karnataka, 560300, India",
        "lat": "13.1989089",
        "lon": "77.7068182",
    },
]


def _nominatim(route: Route) -> None:
    url = urlparse(route.request.url)
    query = parse_qs(url.query)
    if url.path == "/search":
        text = query.get("q", [""])[0].lower()
        body: object = [place for place in PLACES if text in place["name"].lower()]
    else:
        lat, lon = query["lat"][0], query["lon"][0]
        body = {"name": "", "display_name": "Cubbon Road, Bengaluru, Karnataka, India", "lat": lat, "lon": lon}
    route.fulfill(json=body, headers={"Access-Control-Allow-Origin": "*"})


def phone_number() -> str:
    return "7" + "".join(str(random.randint(0, 9)) for _ in range(9))


class Journey:
    """One journey's checks and screens. Every screen it shows is audited with axe as well."""

    def __init__(self, name: str) -> None:
        self.name = name
        self.results: list[tuple[bool, str]] = []
        self.contexts: list[BrowserContext] = []
        self.out = f"{OUT}/{name}"
        os.makedirs(self.out, exist_ok=True)

    def check(self, passed: bool, what: str) -> None:
        self.results.append((bool(passed), what))
        print(("ok   " if passed else "FAIL ") + what, flush=True)

    def context(self, browser: Browser, **extra: object) -> BrowserContext:
        context = browser.new_context(**{**PHONE, **extra})
        self.contexts.append(context)
        if OFFLINE_MAPS:
            context.route("https://nominatim.openstreetmap.org/**", _nominatim)
            context.route("https://tile.openstreetmap.org/**", lambda route: route.abort())
        return context

    def shot(self, page: Page, name: str, full: bool = False) -> None:
        """Saves the screen, and fails a check for anything axe finds on it."""
        page.wait_for_timeout(700)
        page.screenshot(path=f"{self.out}/{name}.png", full_page=full)
        if page.evaluate("typeof axe") == "undefined":
            page.add_script_tag(path=AXE)
        found = page.evaluate(
            "tags => axe.run(document, { runOnly: { type: 'tag', values: tags } })", AXE_TAGS
        )["violations"]
        for violation in found:
            for node in violation["nodes"][:3]:
                print(f"  {violation['id']} at {node['target']}: {node['failureSummary']}", flush=True)
        problems = [f"{v['id']} ({len(v['nodes'])})" for v in found]
        self.check(not problems, f"{name}: no accessibility problems {problems or ''}".rstrip())

    @contextmanager
    def running(self) -> Iterator[None]:
        """Around a journey: one that stops short fails, with a picture of every page it had open."""
        try:
            yield
        except Exception as error:
            traceback.print_exc()
            for i, page in enumerate(page for context in self.contexts for page in context.pages):
                try:
                    page.screenshot(path=f"{self.out}/stopped-{i}.png")
                except Exception:
                    pass
            self.check(False, f"the journey runs to the end ({str(error).splitlines()[0]})")

    def finish(self) -> None:
        failed = [what for passed, what in self.results if not passed]
        line = f"{self.name}: {len(self.results) - len(failed)}/{len(self.results)} checks passed"
        print(line, flush=True)
        with open(f"{OUT}/summary.json", "a", encoding="utf-8") as summary:
            summary.write(json.dumps({"journey": self.name, "results": self.results}) + "\n")
        sys.exit(1 if failed else 0)


def sign_up_rider(page: Page, name: str) -> None:
    page.goto(BASE + "/sign-up")
    page.get_by_label("Name").fill(name)
    number = phone_number()
    page.get_by_label("Phone number").fill(f"{number[:5]} {number[5:]}")
    page.get_by_label("Password", exact=True).fill(PASSWORD)
    page.get_by_role("button", name="Create account").click()


def sign_up_driver(page: Page, name: str) -> str:
    """A driver with a car, waiting for approval. Answers their phone number."""
    number = phone_number()
    page.goto(BASE + "/sign-up?as=driver")
    page.get_by_label("Name").fill(name)
    page.get_by_label("Phone number").fill(f"{number[:5]} {number[5:]}")
    page.get_by_label("Password", exact=True).fill(PASSWORD)
    page.get_by_role("button", name="Continue").click()
    page.get_by_role("radio", name="Car").click()
    page.get_by_label("Plate number").fill(f"KA 05 MN {random.randint(1000, 9999)}")
    page.get_by_label("Model").fill("Maruti Swift")
    page.get_by_label("Colour").fill("White")
    page.get_by_role("button", name="Add vehicle").click()
    return "+91" + number


def approve(playwright: object, driver_phone: str) -> bool:
    """The admin approves the driver, as an admin would, over the API."""
    api = playwright.request.new_context(base_url=BASE)  # type: ignore[attr-defined]
    try:
        admin = api.post(
            "/api/v1/auth/login",
            data={"phone": os.environ["ADMIN_PHONE"], "password": os.environ["ADMIN_PASSWORD"]},
        ).json()["accessToken"]
        driver = api.post("/api/v1/auth/login", data={"phone": driver_phone, "password": PASSWORD}).json()
        me = api.get("/api/v1/users/me", headers={"Authorization": "Bearer " + driver["accessToken"]}).json()
        return api.post(
            f"/api/v1/admin/drivers/{me['id']}/approve", headers={"Authorization": "Bearer " + admin}
        ).ok
    finally:
        api.dispose()


def book_to_indiranagar(page: Page) -> None:
    """From the rider's map to "Finding your driver": Indiranagar by search, a car, cash."""
    page.get_by_role("button", name="Where to?").click()
    expect(page.get_by_role("searchbox", name="Pickup")).to_have_value("Your location", timeout=15_000)
    drop = page.get_by_role("searchbox", name="Drop")
    drop.fill("Indiranagar")
    drop.press("Enter")
    page.get_by_role("list", name="Places matching “Indiranagar”").get_by_role("button").first.click()
    page.get_by_role("button", name="See prices").click()
    page.get_by_role("radio", name="Car").click()
    page.get_by_role("button", name="Confirm Car").click()
