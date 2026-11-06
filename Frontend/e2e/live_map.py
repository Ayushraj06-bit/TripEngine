"""The live map: the driver drives to the pickup and on towards the drop, and the rider watches the
car move, with the time to go - on a phone, and on a window widened mid-trip (ADR 0024)."""

import re
from collections.abc import Iterator

from playwright.sync_api import Page, expect, sync_playwright

from common import MG_ROAD, Journey, approve, book_to_indiranagar, sign_up_driver, sign_up_rider

# About 450 m east of the pickup, driven in 30 m steps every 2.5 s: 43 km/h. The backend refuses a
# position implying more than 150 km/h, and flags a driver who keeps sending them.
START = {"latitude": 12.9750, "longitude": 77.6110, "accuracy": 15}
TOWARDS_DROP = {"latitude": 12.9762, "longitude": 77.6110, "accuracy": 15}
STEP_MS = 2500

journey = Journey("live-map")


def path(a: dict, b: dict, steps: int) -> Iterator[dict]:
    for i in range(1, steps + 1):
        t = i / steps
        yield {
            "latitude": a["latitude"] + (b["latitude"] - a["latitude"]) * t,
            "longitude": a["longitude"] + (b["longitude"] - a["longitude"]) * t,
            "accuracy": 15,
        }


def gap(page: Page, a: str, b: str) -> int:
    """How far apart two markers are drawn, in pixels."""
    one = page.get_by_title(a).bounding_box() or {"x": 0, "y": 0}
    two = page.get_by_title(b).bounding_box() or {"x": 0, "y": 0}
    return round(((one["x"] - two["x"]) ** 2 + (one["y"] - two["y"]) ** 2) ** 0.5)


with sync_playwright() as playwright, journey.running():
    browser = playwright.chromium.launch()

    driving = journey.context(browser, geolocation=START, permissions=["geolocation"])
    driver = driving.new_page()
    phone = sign_up_driver(driver, "Ravi Kumar")
    expect(driver.get_by_role("heading", name="We’re checking your details")).to_be_visible()
    journey.check(approve(playwright, phone), "the admin approves the driver")
    expect(driver.get_by_role("heading", name="Driving")).to_be_visible(timeout=45_000)
    driver.get_by_role("button", name="Go online").click()
    expect(driver.get_by_text("Sharing your location with RideFlow while you’re online.")).to_be_visible(
        timeout=15_000
    )

    rider = journey.context(browser, geolocation=MG_ROAD, permissions=["geolocation"]).new_page()
    sign_up_rider(rider, "Asha Rao")
    book_to_indiranagar(rider)
    expect(rider.get_by_role("heading", name="Finding your driver")).to_be_visible(timeout=15_000)
    journey.check(
        rider.get_by_role("region", name="Map of your trip").is_visible(),
        "the rider waits for a driver on the map",
    )

    driver.get_by_role("region", name="Ride offer").get_by_role("button", name="Accept").click(timeout=30_000)
    expect(driver.get_by_role("heading", name="Head to the pickup")).to_be_visible(timeout=15_000)
    directions = driver.get_by_role("link", name="Open in Maps for directions to the pickup")
    to_pickup = directions.get_attribute("href") or ""
    journey.check("12.9756,77.6069" in to_pickup, "the driver can open directions to the pickup")
    journey.check(driver.get_by_title("You").count() == 1, "the driver sees themselves on the map")
    journey.shot(driver, "driver-to-pickup")

    expect(rider.get_by_title("Your driver")).to_be_attached(timeout=15_000)
    expect(rider.get_by_text(re.compile(r"^Arriving in about \d+ min\.$"))).to_be_visible(timeout=15_000)
    journey.check(True, "the rider sees their driver, and about how long until they arrive")
    journey.shot(rider, "rider-assigned")

    before = gap(rider, "Your driver", "Pickup")
    for point in path(START, MG_ROAD, 15):
        driving.set_geolocation(point)
        driver.wait_for_timeout(STEP_MS)
    driver.wait_for_timeout(2500)
    after = gap(rider, "Your driver", "Pickup")
    journey.check(after < before / 4, f"the car moves to the pickup on the rider’s map ({before} → {after} px)")

    # The last move before stopping reaches the backend: arrival is accepted at once.
    driver.get_by_role("button", name="I’ve arrived").click()
    expect(rider.get_by_role("heading", name="Your driver has arrived")).to_be_visible(timeout=15_000)
    code = "".join(ch for ch in rider.get_by_role("region", name="Your code").inner_text() if ch.isdigit())
    driver.get_by_label("Rider’s code").fill(code)
    driver.get_by_role("button", name="Start trip").click()
    to_drop = driver.get_by_role("link", name="Open in Maps for directions to the drop")
    expect(to_drop).to_be_visible(timeout=15_000)
    journey.check((to_drop.get_attribute("href") or "") != to_pickup, "the directions now go to the drop")

    for point in path(MG_ROAD, TOWARDS_DROP, 15):
        driving.set_geolocation(point)
        driver.wait_for_timeout(STEP_MS)
    expect(rider.get_by_text(re.compile(r"^About \d+ min to the drop\.$"))).to_be_visible(timeout=15_000)
    journey.check(True, "the rider is told about how long to the drop")
    journey.shot(rider, "rider-in-progress")

    # Widened mid-trip: the car and the drop are fitted beside the panel, not left off screen.
    rider.set_viewport_size({"width": 1280, "height": 800})
    rider.wait_for_timeout(2000)
    inside = []
    for title in ("Your driver", "Drop"):
        box = rider.get_by_title(title).bounding_box()
        inside.append(bool(box) and 436 < box["x"] < 1280 and 0 < box["y"] < 800)
    journey.check(all(inside), "widened, the map fits the car and the drop beside the panel")
    journey.shot(rider, "rider-in-progress-1280")
    rider.set_viewport_size({"width": 390, "height": 844})

    driver.get_by_role("button", name="Complete trip").click()
    driver.get_by_role("alertdialog", name="Complete the trip?").get_by_role(
        "button", name="Complete trip"
    ).click()
    expect(rider.get_by_role("heading", name="Trip completed")).to_be_visible(timeout=15_000)
    journey.check(
        rider.get_by_role("region", name="Map of your trip").count() == 0,
        "the map gives way to the fare when the trip completes",
    )
    browser.close()

journey.finish()
