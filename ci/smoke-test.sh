#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the APK, opens the PPF calculator and checks the
# published figure, types a new deposit and an existing balance and start year, checks SCSS's quarterly payout, then on the Loans tab checks a
# home loan's EMI and what a part payment saves, as a shorter loan and as a lower EMI. It also opens EPF + VPF,
# NPS Tier I and the mutual fund lumpsum. Figures that depend on the government's rates come from ci/expected.py,
# so the test keeps passing when the quarterly update changes them.
# Usage: smoke-test.sh <apk> <output dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG=io.github.akrishna87.semippu
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
eval "$(python3 "$HERE/expected.py")"

fail() {
  echo "SMOKE TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/failure.xml" > /dev/null 2>&1; then
    echo "On screen:"; grep -o '\(text\|content-desc\)="[^"]\+"' "$OUT/failure.xml" | head -80 || true
  fi
  adb logcat -d > "$OUT/logcat.txt" || true
  echo "Crashes from the app:"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 || true
  exit 1
}
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1 || true; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap() { # tap <dump name> <text>
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}
wait_for() { # wait_for <dump name> <grep -E pattern> <seconds> <what>
  local end=$((SECONDS + $3))
  while [ $SECONDS -lt $end ]; do
    dump "$1"
    [ -f "$OUT/$1.xml" ] && grep -Eq "$2" "$OUT/$1.xml" && return 0
    sleep 2
  done
  fail "$4 (waited $3 s)"
}

adb wait-for-device
adb install -r "$APK"
adb shell settings put global hide_error_dialogs 1 || true
adb logcat -c

echo "--- Home"
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
wait_for home 'text="Public Provident Fund"' 30 "Home doesn't list the schemes"
grep -qF "text=\"Government rates for $QUARTER\"" "$OUT/home.xml" || fail "Home doesn't say which quarter's rates it uses"
shot 01-home
echo "PASS: Home"

echo "--- PPF"
tap home "PPF"
wait_for ppf "text=\"$PPF_DEFAULT\"" 15 "PPF doesn't show $PPF_DEFAULT for ₹1.5 lakh a year for 15 years"
shot 02-ppf
echo "PASS: PPF default"

echo "--- Typing a new deposit"
tap ppf "150000"
adb shell input keyevent KEYCODE_MOVE_END
for _ in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DEL; done
adb shell input text 50000
wait_for ppf-typed "text=\"$PPF_TYPED\"" 15 "₹50,000 a year doesn't give $PPF_TYPED"
shot 03-ppf-typed
echo "PASS: typing"

hide_keyboard() { if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi; }

echo "--- PPF already open: ₹5 lakh in it, opened 4 years ago"
hide_keyboard
dump ppf-open
tap ppf-open "Balance today (₹)"
adb shell input text 500000
hide_keyboard
dump ppf-open2
tap ppf-open2 "Year you started"
adb shell input text $(( $(date +%Y) - 4 ))
hide_keyboard
adb shell input swipe 540 1800 540 900 600
sleep 1
# ₹5 lakh grown for the 11 years left, plus ₹50,000 at the start of each: ₹19,13,000 at 7.1%.
wait_for ppf-running "text=\"$PPF_RUNNING\"" 15 "an account opened 4 years ago with ₹5 lakh doesn't mature at $PPF_RUNNING"
grep -q 'text="Maturity value after 11 years"' "$OUT/ppf-running.xml" || fail "it doesn't count the 4 years already done"
shot 03b-ppf-running
echo "PASS: PPF already open"

adb shell input keyevent KEYCODE_BACK
wait_for home2 'text="Public Provident Fund"' 15 "Back doesn't return to the list"

echo "--- SCSS"
tap home2 "SCSS"
wait_for scss "text=\"$SCSS_QUARTER every quarter\"" 15 "SCSS doesn't pay $SCSS_QUARTER a quarter on ₹15 lakh"
shot 04-scss
echo "PASS: SCSS"

adb shell input keyevent KEYCODE_BACK
wait_for home3 'text="Public Provident Fund"' 15 "Back doesn't return to the list"

echo "--- EPF"
tap home3 "EPF"
wait_for epf 'text="₹1,31,16,657"' 15 "₹30,000 basic from 30 to 58 with 5% raises doesn't come to ₹1,31,16,657"
adb shell input swipe 540 1800 540 900 600   # the shares are below the seven fields
sleep 1
wait_for epf-shares 'text="Your employer puts in"' 15 "EPF doesn't show the employer's share"
shot 04b-epf
echo "PASS: EPF"
adb shell input keyevent KEYCODE_BACK
wait_for home3 'text="Public Provident Fund"' 15 "Back doesn't return to the list"

scroll_to() { # scroll_to <dump name> <text>: swipes up the list until <text> is on screen
  for _ in 1 2 3 4 5 6; do
    dump "$1"
    grep -qF "text=\"$2\"" "$OUT/$1.xml" && return 0
    adb shell input swipe 540 1700 540 1000 400
    sleep 1
  done
  fail "couldn't find '$2' in the list"
}

echo "--- NPS Tier I"
scroll_to home-nps "NPS Tier I"
tap home-nps "NPS Tier I"
# ₹5,000 a month from 30 to 60 at 10%: ₹1,13,96,627; 20% buys an annuity paying 6%.
wait_for nps 'text="NPS Tier I"' 15 "NPS Tier I doesn't open"
scroll_to nps-total "₹1,13,96,627"   # the result is below the ten fields
scroll_to nps-split "Pension a month, at 6%"
shot 04c-nps
echo "PASS: NPS Tier I"
adb shell input keyevent KEYCODE_BACK
wait_for home4 'text="Public Provident Fund"' 15 "Back doesn't return to the list"

echo "--- Lumpsum"
scroll_to home-lump "Lumpsum"
tap home-lump "Lumpsum"
wait_for lumpsum 'text="₹3,10,585"' 15 "₹1 lakh for 10 years at 12% doesn't come to ₹3,10,585"
shot 04d-lumpsum
echo "PASS: Lumpsum"
adb shell input keyevent KEYCODE_BACK
wait_for home3 'text="Public Provident Fund"' 15 "Back doesn't return to the list"

echo "--- Loans"
tap home3 "Loans"
wait_for loans 'text="Home loan"' 15 "the Loans tab doesn't list the loans"
shot 05-loans
tap loans "Home loan"
wait_for home-loan 'text="₹43,391"' 15 "a ₹50 lakh home loan at 8.5% for 20 years doesn't show an EMI of ₹43,391"
shot 06-home-loan
echo "PASS: EMI"

echo "--- Part payment"
swipe_up() { adb shell input swipe 540 1800 540 800 600; sleep 1; }
swipe_up
dump part
tap part "Part payment (₹)"
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keyevent KEYCODE_DEL
adb shell input text 100000
sleep 1
adb shell input keyevent KEYCODE_BACK   # hides the keyboard
sleep 1
swipe_up
wait_for saved 'text="11 months sooner"' 15 "₹1 lakh paid after the first EMI doesn't end the loan 11 months sooner"
grep -q 'text="₹4,19,612"' "$OUT/saved.xml" || fail "the interest saved isn't ₹4,19,612"
shot 07-part-payment
echo "PASS: part payment, shorter loan"

adb shell input swipe 540 900 540 1400 600
sleep 1
dump choice
tap choice "Lower EMI"
sleep 1
swipe_up
wait_for lower 'text="₹42,522"' 15 "the lower EMI isn't ₹42,522"
shot 08-lower-emi
echo "PASS: part payment, lower EMI"

adb logcat -d | grep -q "FATAL EXCEPTION" && fail "the app crashed"
echo "Smoke test passed."
