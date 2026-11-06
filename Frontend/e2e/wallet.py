"""The wallet: a top-up paid at the demo's test gateway, its outcome asked rather than guessed, and
a driver's earnings (ADR 0025)."""

import re

from playwright.sync_api import expect, sync_playwright

from common import MG_ROAD, Journey, approve, sign_up_driver, sign_up_rider

journey = Journey("wallet")

with sync_playwright() as playwright, journey.running():
    browser = playwright.chromium.launch()

    rider = journey.context(browser, geolocation=MG_ROAD, permissions=["geolocation"]).new_page()
    sign_up_rider(rider, "Asha Rao")
    rider.get_by_role("link", name="Wallet").click()
    balance = rider.get_by_role("region", name="Balance")
    expect(balance).to_contain_text("₹0", timeout=15_000)
    journey.check(rider.get_by_text("Nothing yet.").is_visible(), "a new wallet opens from the map, empty")
    journey.shot(rider, "wallet-new")

    rider.get_by_role("radio", name="Other").click()
    rider.get_by_label("Amount in rupees").fill("5")
    rider.get_by_role("button", name="Add ₹5").click()
    expect(rider.get_by_text("Add between ₹10 and ₹10,000 at a time.")).to_be_visible(timeout=15_000)
    journey.check(True, "an amount under the limit is refused, in the backend’s limits")
    journey.shot(rider, "wallet-refused")

    rider.get_by_role("radio", name="₹200").click()
    rider.get_by_role("button", name="Add ₹200").click()
    expect(rider.get_by_text("₹200 added to your wallet.")).to_be_visible(timeout=40_000)
    journey.check(True, "paid at the test gateway, the top-up is confirmed")
    expect(balance).to_contain_text("₹200", timeout=15_000)
    expect(rider.get_by_role("listitem").first).to_have_text(re.compile(r"^Top-up.*\+₹200$"))
    journey.check(True, "the balance and the activity show the money")
    journey.shot(rider, "wallet-topped-up", full=True)

    rider.get_by_role("link", name="Done").click()
    expect(rider.get_by_role("button", name="Where to?")).to_be_visible(timeout=15_000)
    journey.check(True, "Done leads back to the map")

    driver = journey.context(browser, geolocation=MG_ROAD, permissions=["geolocation"]).new_page()
    phone = sign_up_driver(driver, "Ravi Kumar")
    expect(driver.get_by_role("heading", name="We’re checking your details")).to_be_visible()
    journey.check(approve(playwright, phone), "the admin approves the driver")
    expect(driver.get_by_role("heading", name="Driving")).to_be_visible(timeout=45_000)
    driver.get_by_role("link", name="Earnings").click()
    expect(driver.get_by_role("region", name="Balance")).to_contain_text(
        "Fares paid by wallet come in here", timeout=15_000
    )
    journey.check(True, "a new driver’s earnings open, and say what will come in")
    journey.shot(driver, "earnings")
    browser.close()

journey.finish()
