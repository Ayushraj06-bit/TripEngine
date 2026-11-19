"""A whole trip on two phones: the offer, arrival, the rider's code, the fare - then each side rates
the other and finds the trip in their own list (ADRs 0022, 0023, 0026)."""

import re

from playwright.sync_api import expect, sync_playwright

from common import MG_ROAD, Journey, approve, book_to_indiranagar, sign_up_driver, sign_up_rider

journey = Journey("trip")

with sync_playwright() as playwright, journey.running():
    browser = playwright.chromium.launch()

    # The driver: onboarded, approved - the approval page moves on by itself - and online.
    driver = journey.context(browser, geolocation=MG_ROAD, permissions=["geolocation"]).new_page()
    phone = sign_up_driver(driver, "Ravi Kumar")
    expect(driver.get_by_role("heading", name="We’re checking your details")).to_be_visible()
    journey.check(approve(playwright, phone), "the admin approves the driver")
    expect(driver.get_by_role("heading", name="Driving")).to_be_visible(timeout=45_000)
    journey.check(True, "the approval page moves on by itself")
    driver.get_by_role("button", name="Go online").click()
    expect(driver.get_by_text("Sharing your location with RideFlow while you’re online.")).to_be_visible(
        timeout=15_000
    )
    journey.shot(driver, "driver-online")

    # The rider books at MG Road, where the driver is.
    rider = journey.context(browser, geolocation=MG_ROAD, permissions=["geolocation"]).new_page()
    sign_up_rider(rider, "Asha Rao")
    book_to_indiranagar(rider)
    expect(rider.get_by_role("heading", name="Finding your driver")).to_be_visible(timeout=15_000)

    offer = driver.get_by_role("region", name="Ride offer")
    expect(offer).to_be_visible(timeout=30_000)
    journey.check(True, "the offer reaches the driver without a reload")
    journey.shot(driver, "offer")
    offer.get_by_role("button", name="Accept").click()
    expect(driver.get_by_role("heading", name="Head to the pickup")).to_be_visible(timeout=15_000)
    expect(rider.get_by_role("heading", name="Your driver is on the way")).to_be_visible(timeout=15_000)
    journey.check(True, "acceptance reaches the rider without a reload")
    journey.shot(rider, "rider-assigned")

    expect(driver.get_by_text("You’re at the pickup. Mark your arrival.")).to_be_visible(timeout=15_000)
    driver.get_by_role("button", name="I’ve arrived").click()
    expect(rider.get_by_role("heading", name="Your driver has arrived")).to_be_visible(timeout=15_000)
    expect(rider.get_by_role("timer", name=re.compile("^Free waiting for"))).to_be_visible()
    journey.check(True, "arrival reaches the rider, with the free waiting counted down")
    journey.shot(driver, "driver-arrived")

    # The rider reads out the code; the driver types it, spaced as said.
    code = "".join(ch for ch in rider.get_by_role("region", name="Your code").inner_text() if ch.isdigit())
    driver.get_by_label("Rider’s code").fill(f"{code[:2]} {code[2:]}")
    driver.get_by_role("button", name="Start trip").click()
    expect(rider.get_by_role("heading", name="On your way")).to_be_visible(timeout=15_000)
    journey.check(True, "the trip starts with the rider’s code, and the rider sees it")

    driver.get_by_role("button", name="Complete trip").click()
    driver.get_by_role("alertdialog", name="Complete the trip?").get_by_role(
        "button", name="Complete trip"
    ).click()
    expect(driver.get_by_text(re.compile(r"^Collect ₹[\d,]+ in cash from the rider\.$"))).to_be_visible(
        timeout=15_000
    )
    journey.check(True, "the driver is told what to collect")
    expect(rider.get_by_role("region", name="Fare")).to_contain_text("Paid in cash", timeout=15_000)
    journey.check(True, "the rider sees the fare, paid in cash")

    # Both rate: the rider the ride, the driver the rider.
    form = rider.get_by_role("form", name="Rate this trip")
    form.get_by_text("★").nth(4).click()
    form.get_by_label("A few words (optional)").fill("Smooth ride, on time.")
    journey.shot(rider, "rider-rating", full=True)
    form.get_by_role("button", name="Send rating").click()
    expect(rider.get_by_text("Thanks. Your rating has been sent.")).to_be_visible(timeout=15_000)
    journey.check(True, "the rider rates the ride")
    rating = driver.get_by_role("form", name="Rate this trip")
    rating.get_by_text("★").nth(3).click()
    rating.get_by_role("button", name="Send rating").click()
    expect(driver.get_by_text("Thanks. Your rating has been sent.")).to_be_visible(timeout=15_000)
    journey.check(True, "the driver rates the rider")

    # Home is home, not the finished trip; there, the trip is in the rider's list, rated.
    rider.get_by_role("link", name="Home").click()
    rider.get_by_role("link", name="Your trips").click()
    row = rider.get_by_role("list").get_by_role("link").first
    # The drop by the name the rider chose; the pickup was their device's position, which has none.
    expect(row).to_have_text(re.compile(r"^Completed · CarTo Indiranagar.*₹[\d,]+$"), timeout=15_000)
    journey.check(True, "the rider goes home, and finds the trip in their trips, named, with its fare")
    journey.shot(rider, "rider-trips")
    row.click()
    expect(rider.get_by_text("You’ve rated this trip.")).to_be_visible(timeout=15_000)
    journey.check(True, "opened again, the trip is not offered for rating twice")
    journey.check(rider.get_by_text("To Indiranagar").is_visible(), "opened again, the trip says where it went")

    driver.get_by_role("link", name="Back to driving").click()
    stars = driver.get_by_text("Rated 5.0 by riders, from 1 rating.")
    for _ in range(10):
        if stars.count():
            break
        driver.wait_for_timeout(2000)
        driver.reload()
    expect(stars).to_be_visible(timeout=15_000)
    journey.check(True, "the driver goes back to driving, and sees the rider’s five stars")
    journey.shot(driver, "driver-rated")
    driver.get_by_role("link", name="Your trips").click()
    expect(driver.get_by_role("list").get_by_role("link").first).to_have_text(
        re.compile(r"^Completed · CarTo Indiranagar"), timeout=15_000
    )
    journey.check(True, "the driver finds the trip in their trips, named")
    browser.close()

journey.finish()
