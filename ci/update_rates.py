"""Update the government small savings rates from India Post's official page.

The Ministry of Finance notifies the rates for each quarter, usually on the last working day of the quarter before.
The Department of Posts publishes them on https://www.indiapost.gov.in/banking-services/savings. This reads that
page, checks every rate is there and sensible, and rewrites `Rates` in Schemes.kt and the rates in README.md for
the quarter containing today (India time). Nothing changes when the page has the same rates for the same quarter.

Usage: update_rates.py [--html saved-page.html] [--today YYYY-MM-DD]
Prints what changed. Exits non-zero if the page can't be read or a rate is missing or out of range, so the
scheduled workflow fails loudly instead of publishing wrong rates.
"""
import argparse
import datetime as dt
import html
import math
import os
import re
import sys
import time
import urllib.request

URL = "https://www.indiapost.gov.in/banking-services/savings"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMES = os.path.join(ROOT, "app/src/main/java/io/github/akrishna87/semippu/Schemes.kt")
README = os.path.join(ROOT, "README.md")

# The constant in Rates, the abbreviation India Post puts after the scheme's name, and the README row.
SCHEMES_ON_PAGE = [
    ("PPF", "PPF", "PPF"),
    ("SSY", "SSA", "Sukanya Samriddhi"),
    ("NSC", "NSC", "NSC"),
    ("KVP", "KVP", "KVP"),
    ("SCSS", "SCSS", "SCSS"),
    ("MIS", "MIS", "Monthly Income Scheme"),
    ("RD", "RD", "Recurring Deposit"),
]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]


def fetch(url: str) -> str:
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Selvam rates updater)"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # network errors, 5xx
            last = e
            time.sleep(10 * (attempt + 1))
    sys.exit(f"Couldn't read {url}: {last}")


def page_text(raw: str) -> str:
    raw = re.sub(r"<script.*?</script>|<style.*?</style>", " ", raw, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", raw)))


def parse_rates(text: str) -> dict:
    rates, missing = {}, []
    for const, abbr, _ in SCHEMES_ON_PAGE:
        m = re.search(r"\(" + abbr + r"\)\s*Rate of Interest:\s*(\d+(?:\.\d+)?)\s*%", text)
        if not m:
            missing.append(abbr)
            continue
        value = float(m.group(1))
        if not 2.0 <= value <= 15.0:
            sys.exit(f"{abbr}'s rate on the page is {value}%, which doesn't look right; not updating.")
        rates[const] = value
    if missing:
        sys.exit(f"India Post's page no longer shows a rate for: {', '.join(missing)}. The page may have changed.")
    return rates


def quarter(today: dt.date):
    first_month = (today.month - 1) // 3 * 3 + 1
    start = dt.date(today.year, first_month, 1)
    end = (dt.date(today.year + (first_month == 10), (first_month + 2) % 12 + 1, 1) - dt.timedelta(days=1))
    short = f"{start.day} {MONTHS[start.month - 1][:3]} – {end.day} {MONTHS[end.month - 1][:3]} {end.year}"
    long = f"{start.day} {MONTHS[start.month - 1]} – {end.day} {MONTHS[end.month - 1]} {end.year}"
    return short, long, end.isoformat()


def kvp_months(rate: float) -> int:
    # The same as kvpMonths() in Schemes.kt: Math.round of the months to double, compounded yearly.
    return math.floor(math.log(2) / math.log(1 + rate / 100) * 12 + 0.5)


def num(x: float) -> str:
    return str(int(x)) if x == int(x) else str(x)


def update_schemes(src: str, rates: dict, short: str, end: str) -> str:
    src = re.sub(r'(const val QUARTER = )"[^"]*"', lambda m: f'{m.group(1)}"{short}"', src, count=1)
    src = re.sub(r'(const val QUARTER_END = )"[^"]*"', lambda m: f'{m.group(1)}"{end}"', src, count=1)
    for const, value in rates.items():
        src, n = re.subn(r"(\n    const val " + const + r" = )[\d.]+", lambda m: m.group(1) + num(value), src, count=1)
        if n != 1:
            sys.exit(f"Couldn't find Rates.{const} in Schemes.kt")
    return src


def update_readme(src: str, rates: dict, long: str) -> str:
    src = re.sub(r"(The government rates are those for \*\*)[^*]+(\*\*)", lambda m: m.group(1) + long + m.group(2), src)
    for const, _, row in SCHEMES_ON_PAGE:
        src = re.sub(r"(\n\| " + re.escape(row) + r" \| )[\d.]+%", lambda m: f"{m.group(1)}{num(rates[const])}%", src,
                     count=1)
    months = kvp_months(rates["KVP"])
    src = re.sub(r"(Doubles your money; )\d+ months( at the current rate)", lambda m: f"{m.group(1)}{months} months{m.group(2)}",
                 src, count=1)
    return src


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--html", help="read this saved copy of the page instead of fetching it")
    ap.add_argument("--today", help="the date to work out the quarter from (default: today in India)")
    args = ap.parse_args()

    raw = open(args.html, encoding="utf-8").read() if args.html else fetch(URL)
    text = page_text(raw)
    rates = parse_rates(text)
    updated = re.search(r"Last Updated On:\s*([\d.]+)", text)
    ist = dt.datetime.now(dt.timezone(dt.timedelta(hours=5, minutes=30))).date()
    today = dt.date.fromisoformat(args.today) if args.today else ist
    short, long, end = quarter(today)

    print(f"India Post ({URL}), page last updated {updated.group(1) if updated else 'unknown'}:")
    for const, value in rates.items():
        print(f"  {const}: {num(value)}%")
    print(f"Quarter: {long}")

    old_schemes = open(SCHEMES, encoding="utf-8").read()
    old_readme = open(README, encoding="utf-8").read()
    new_schemes = update_schemes(old_schemes, rates, short, end)
    new_readme = update_readme(old_readme, rates, long)
    changes = []
    for const, _, _ in SCHEMES_ON_PAGE:
        before = re.search(r"\n    const val " + const + r" = ([\d.]+)", old_schemes).group(1)
        if float(before) != rates[const]:
            changes.append(f"{const} {before}% → {num(rates[const])}%")
    if new_schemes != old_schemes:
        open(SCHEMES, "w", encoding="utf-8").write(new_schemes)
    if new_readme != old_readme:
        open(README, "w", encoding="utf-8").write(new_readme)
    summary = ("Rates changed: " + ", ".join(changes)) if changes else "No rate changed"
    print(summary)

    # For the workflow: the commit message, and a note on the run's page.
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"quarter={long}\nsummary={summary}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(f"### Rates for {long}\n\nFrom [India Post]({URL}).\n\n| Scheme | Rate |\n|---|---|\n")
            f.writelines(f"| {row} | {num(rates[c])}% |\n" for c, _, row in SCHEMES_ON_PAGE)
            f.write(f"\n{summary}.\n")


if __name__ == "__main__":
    main()
