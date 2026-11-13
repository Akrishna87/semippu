"""Print what the smoke test should see on screen with the rates in Schemes.kt, so the test still passes after
the quarterly rates update. Each line is NAME=VALUE, for `eval` in bash.
"""
import math
import os
import re

src = open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app/src/main/java/io/github/akrishna87/semippu/Schemes.kt"), encoding="utf-8").read()


def rate(name):
    return float(re.search(r"\n    const val " + name + r" = ([\d.]+)", src).group(1))


def rupees(x):
    # The same as rupees() in Schemes.kt: Math.round, then Indian grouping.
    s = str(math.floor(x + 0.5))
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        s = ",".join(([head] if head else []) + groups) + "," + tail
    return "₹" + s


def ppf(deposit, years, r, start=0.0):
    b = start
    for _ in range(years):
        b = (b + deposit) * (1 + r / 100)
    return b


quarter = re.search(r'const val QUARTER = "([^"]*)"', src).group(1)
p = rate("PPF")
print(f"QUARTER='{quarter}'")
print(f"PPF_DEFAULT='{rupees(ppf(150000.0, 15, p))}'")
print(f"PPF_TYPED='{rupees(ppf(50000.0, 15, p))}'")
print(f"PPF_RUNNING='{rupees(ppf(50000.0, 11, p, 500000.0))}'")
print(f"SCSS_QUARTER='{rupees(1500000.0 * rate('SCSS') / 100 / 4)}'")
