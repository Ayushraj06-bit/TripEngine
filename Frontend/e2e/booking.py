"""Where to and booking: the device's location, search, the map, the service area, prices, and a
double tap that books once (ADRs 0020, 0021)."""

import re

from playwright.sync_api import expect, sync_playwright

from common import MG_ROAD, Journey, sign_up_rider

journey = Journey("booking")

with sync_playwright() as playwright, journey.running():
    browser = playwright.chromium.launch()

    # Location allowed, at MG Road.
    rider = journey.context(browser, geolocation=MG_ROAD, permissions=["geolocation"]).new_page()
    searches: list[str] = []
    rider.on("request", lambda r: "nominatim.openstreetmap.org/search" in r.url and searches.append(r.url))
    bookings: list[str | None] = []
    rider.on(
        "request",
        lambda r: r.method == "POST"
        and r.url.endswith("/api/v1/trips")
        and bookings.append(r.headers.get("idempotency-key")),
    )
    sign_up_rider(rider, "Asha Rao")
    expect(rider.get_by_role("button", name="Where to?")).to_be_visible(timeout=15_000)
    journey.shot(rider, "home")

    rider.get_by_role("button", name="Where to?").click()
    expect(rider.get_by_role("searchbox", name="Pickup")).to_have_value("Your location")
    journey.check(True, "Where to fills the pickup from the device")
    drop = rider.get_by_role("searchbox", name="Drop")
    drop.press_sequentially("Indiranagar", delay=40)
    journey.check(not searches, "nothing is searched while typing")
    drop.press("Enter")
    found = rider.get_by_role("list", name="Places matching “Indiranagar”")
    expect(found).to_be_visible(timeout=15_000)
    journey.check(len(searches) == 1, f"one search on Enter ({len(searches)} sent)")
    journey.shot(rider, "where-to")

    found.get_by_role("button").first.click()
    expect(rider.get_by_role("region", name="Your ride")).to_be_visible()
    rider.reload()
    expect(rider.get_by_role("region", name="Your ride")).to_be_visible(timeout=15_000)
    journey.check(True, "a reload keeps the pickup and the drop")

    # The drop moved on the map with the keyboard, and confirmed.
    rider.get_by_role("button", name="Change drop").click()
    rider.get_by_role("button", name="Choose on the map").click()
    confirm = rider.get_by_role("button", name="Confirm drop")
    expect(confirm).to_be_visible()
    rider.locator(".rf-map .leaflet-container").focus()
    for _ in range(4):
        rider.keyboard.press("ArrowDown")
    rider.wait_for_timeout(2500)
    journey.shot(rider, "choose-on-map")
    confirm.click()
    expect(rider.get_by_role("region", name="Your ride")).to_be_visible()
    journey.check(True, "the drop is moved on the map and confirmed")

    # A place outside the service area is found, and said to be out of reach.
    rider.get_by_role("button", name="Change drop").click()
    drop = rider.get_by_role("searchbox", name="Drop")
    drop.fill("Kempegowda International Airport")
    drop.press("Enter")
    outside = rider.get_by_role("button", name="Kempegowda International Airport").first
    expect(outside).to_be_visible(timeout=15_000)
    journey.check(outside.is_disabled(), "a place outside the service area cannot be chosen")
    journey.shot(rider, "outside")

    drop.fill("Indiranagar")
    drop.press("Enter")
    rider.get_by_role("list", name="Places matching “Indiranagar”").get_by_role("button").first.click()
    rider.get_by_role("button", name="See prices").click()
    rides = rider.get_by_role("radiogroup", name="Rides")
    expect(rides).to_be_visible(timeout=15_000)
    journey.check(rides.get_by_role("radio").count() == 3, "three ride types are priced")
    timer = rider.get_by_role("timer").get_attribute("aria-label") or ""
    # To the second either way, as the unit tests allow: the server's clock is read from the Date
    # header, which is cut to the second and stamped before the answer travels.
    journey.check(
        re.search(r"Prices valid for (4:5\d|5:0[01])", timer) is not None,
        f"the prices count down from five minutes ({timer})",
    )
    rider.get_by_text("How this fare is made").click()
    journey.shot(rider, "prices")

    rides.get_by_role("radio", name="Auto").click()
    rider.get_by_role("button", name="Confirm Auto").dblclick()
    expect(rider.get_by_role("heading", name="Finding your driver")).to_be_visible(timeout=15_000)
    journey.check(len(bookings) == 1, f"a double tap books once ({len(bookings)} sent)")
    journey.check(bool(bookings and bookings[0]), "the booking carries an Idempotency-Key")
    journey.shot(rider, "finding")

    # Free while no driver is found - and no trip left behind for the next journey's driver.
    rider.get_by_role("button", name="Cancel ride").click()
    expect(rider.get_by_role("heading", name="Trip cancelled")).to_be_visible(timeout=15_000)
    journey.check(True, "cancelling while a driver is being found is immediate")

    # Location refused: the app says what to do instead.
    refused = journey.context(browser).new_page()
    sign_up_rider(refused, "Ravi Kumar")
    refused.get_by_role("button", name="Where to?").click()
    expect(refused.get_by_text("Location is off for RideFlow.")).to_be_visible(timeout=15_000)
    journey.check(True, "a refused location says what to do instead")
    journey.shot(refused, "location-refused")
    browser.close()

journey.finish()
