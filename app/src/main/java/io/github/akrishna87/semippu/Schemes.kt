package io.github.akrishna87.semippu

import java.time.Year
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Small savings rates notified by the Ministry of Finance. Update these every quarter. */
object Rates {
    const val QUARTER = "1 Oct – 31 Dec 2026"
    const val PPF = 7.1
    const val SSY = 8.2
    const val NSC = 7.7
    const val KVP = 7.5
    const val SCSS = 8.2
    const val MIS = 7.4
    const val RD = 6.7

    /** EPF's rate is declared once a year by EPFO, not every quarter. */
    const val EPF_YEAR = "2025-26"
    const val EPF = 8.25
}

/**
 * A number the user types. A value must be in [min, max] and, when [step] is set, min + a whole number of steps.
 * An [optional] field can be left blank; the calculation then doesn't get it.
 */
data class Field(
    val key: String,
    val label: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val step: Double? = null,
    val hint: String = "",
    val optional: Boolean = false,
) {
    fun error(value: Double?): String? = when {
        value == null -> if (optional) null else "Enter a number"
        value < min || value > max -> "From ${plain(min)} to ${plain(max)}"
        step != null && abs((value - min) / step - ((value - min) / step).roundToInt()) > 1e-9 ->
            if (min == step) "In multiples of ${plain(step)}" else "${plain(min)}, then steps of ${plain(step)}"
        else -> null
    }
}

/** One line of the year-by-year table. */
data class Row(val label: String, val deposited: Double, val interest: Double, val balance: Double)

data class Result(
    val invested: Double,
    /** All the interest earned, including any paid out along the way. */
    val interest: Double,
    /** What you get back at the end. */
    val maturity: Double,
    val months: Int,
    /** Interest paid out regularly (SCSS, MIS), with how often. */
    val payout: Double? = null,
    val payoutEvery: String? = null,
    val rows: List<Row>,
    /** Money already in the account today, which [interest] and [rows] build on. */
    val start: Double = 0.0,
    /** Who [invested] came from, when it's more than one person (EPF: you and your employer). */
    val parts: List<Pair<String, Double>> = emptyList(),
)

class Scheme(
    val id: String,
    val name: String,
    val fullName: String,
    val group: String,
    val fields: List<Field>,
    val notes: String,
    /** A problem between fields, as the key of the field to show it on and the message. */
    val check: (Map<String, Double>) -> Pair<String, String>? = { null },
    val calc: (Map<String, Double>) -> Result,
)

private fun rate(default: Double, hint: String = "") =
    Field("rate", "Interest rate (% a year)", default, 0.1, 30.0, hint = hint)

private fun balance(label: String = "Balance today (₹)", hint: String = "Optional: what's in the account already") =
    Field("balance", label, 0.0, 0.0, 1e10, hint = hint, optional = true)

private val thisYear = Year.now().value

private fun since(maxYears: Int) = Field(
    "since", "Year you started", thisYear.toDouble(), (thisYear - maxYears + 1).toDouble(), thisYear.toDouble(),
    step = 1.0, hint = "Optional: for an account that's already open", optional = true,
)

/** Whole years since the account was opened, or 0 for a new one. */
private fun done(v: Map<String, Double>) = v["since"]?.let { thisYear - it.toInt() } ?: 0

val schemes = listOf(
    Scheme(
        "ppf", "PPF", "Public Provident Fund", "Government schemes",
        listOf(
            Field("deposit", "Deposit each year (₹)", 150000.0, 500.0, 150000.0),
            Field("years", "Years", 15.0, 15.0, 50.0, step = 5.0, hint = "15, then blocks of 5, from when it was opened"),
            rate(Rates.PPF),
            balance(),
            since(50),
        ),
        "15 years, which you can extend in blocks of 5. ₹500 to ₹1.5 lakh a year. Assumes you deposit by 5 April " +
            "each year, so the whole year earns interest. The interest is tax-free. For an account that's already " +
            "open, enter its balance and the year you opened it: it grows from today's balance.",
        check = { v ->
            val years = v.getValue("years").toInt()
            if (done(v) >= years) "since" to "That's $years years or more ago: extend the years above" else null
        },
    ) { ppf(it.getValue("deposit"), it.getValue("years").toInt(), it.getValue("rate"), it["balance"] ?: 0.0, done(it)) },
    Scheme(
        "ssy", "Sukanya Samriddhi", "Sukanya Samriddhi Yojana", "Government schemes",
        listOf(
            Field("deposit", "Deposit each year (₹)", 150000.0, 250.0, 150000.0),
            rate(Rates.SSY),
            balance(),
            since(21),
        ),
        "For a girl under 10. You deposit for 15 years; the account matures 21 years after it was opened and keeps " +
            "earning interest in between. ₹250 to ₹1.5 lakh a year. Assumes you deposit by 5 April each year. " +
            "The interest is tax-free. For an account that's already open, enter its balance and the year you opened it.",
    ) { ssy(it.getValue("deposit"), it.getValue("rate"), it["balance"] ?: 0.0, done(it)) },
    Scheme(
        "nsc", "NSC", "National Savings Certificate", "Government schemes",
        listOf(
            Field("amount", "Amount (₹)", 100000.0, 1000.0, 1e9, step = 100.0, hint = "From ₹1,000, in ₹100s"),
            rate(Rates.NSC),
        ),
        "5 years. Interest is added every year and paid at maturity. From ₹1,000, in multiples of ₹100. " +
            "The interest is taxable, but each year's interest counts towards 80C.",
    ) { nsc(it.getValue("amount"), it.getValue("rate")) },
    Scheme(
        "kvp", "KVP", "Kisan Vikas Patra", "Government schemes",
        listOf(
            Field("amount", "Amount (₹)", 100000.0, 1000.0, 1e9, step = 100.0, hint = "From ₹1,000, in ₹100s"),
            rate(Rates.KVP),
        ),
        "Doubles your money; at the current rate that takes 115 months. From ₹1,000, in multiples of ₹100. " +
            "The interest is taxable.",
    ) { kvp(it.getValue("amount"), it.getValue("rate")) },
    Scheme(
        "scss", "SCSS", "Senior Citizens Savings Scheme", "Government schemes",
        listOf(
            Field("amount", "Amount (₹)", 1500000.0, 1000.0, 3000000.0, step = 1000.0, hint = "Up to ₹30 lakh, in ₹1,000s"),
            rate(Rates.SCSS),
        ),
        "For people aged 60 and over (55 for some retirees). ₹1,000 to ₹30 lakh, in multiples of ₹1,000. " +
            "Interest is paid every quarter, and the deposit comes back after 5 years. The interest is taxable.",
    ) { payout(it.getValue("amount"), it.getValue("rate"), perYear = 4, every = "every quarter") },
    Scheme(
        "mis", "Monthly Income Scheme", "Post Office Monthly Income Scheme", "Government schemes",
        listOf(
            Field(
                "amount", "Amount (₹)", 900000.0, 1000.0, 1500000.0, step = 1000.0,
                hint = "Up to ₹9 lakh single, ₹15 lakh joint",
            ),
            rate(Rates.MIS),
        ),
        "Up to ₹9 lakh in a single account or ₹15 lakh in a joint one, in multiples of ₹1,000. Interest is paid " +
            "every month, and the deposit comes back after 5 years. The interest is taxable.",
    ) { payout(it.getValue("amount"), it.getValue("rate"), perYear = 12, every = "every month") },
    Scheme(
        "epf", "EPF", "Employees' Provident Fund", "Retirement",
        listOf(
            Field("basic", "Basic pay + DA a month (₹)", 30000.0, 1.0, 1e8),
            Field("age", "Your age", 30.0, 18.0, 59.0, step = 1.0),
            Field("retire", "Retire at", 58.0, 19.0, 60.0, step = 1.0, hint = "EPF is paid out at 58"),
            Field("raise", "Pay rise each year (%)", 5.0, 0.0, 30.0),
            Field("rate", "Interest rate (% a year)", Rates.EPF, 0.1, 30.0, hint = "Rate for ${Rates.EPF_YEAR}"),
            Field("vpf", "VPF (% of basic + DA)", 0.0, 0.0, 88.0, hint = "Optional: extra you choose to put in", optional = true),
            balance(hint = "Optional: from your EPF passbook"),
        ),
        "You put in 12% of basic + DA, and your employer 12%: 3.67% goes to your EPF and 8.33% to the pension " +
            "scheme (EPS), at most ₹1,250 a month. VPF is extra you can choose to add, up to all of your basic, and " +
            "earns the same rate. Interest is worked out on each month's balance and added once a year. Interest on " +
            "more than ₹2.5 lakh a year of your own contributions is taxable. The EPS pension isn't included here.",
        check = { v -> if (v.getValue("retire") <= v.getValue("age")) "retire" to "Later than your age" else null },
    ) {
        epf(
            it.getValue("basic"), it.getValue("age").toInt(), it.getValue("retire").toInt(), it.getValue("raise"),
            it.getValue("rate"), it["vpf"] ?: 0.0, it["balance"] ?: 0.0,
        )
    },
    Scheme(
        "fd", "Fixed Deposit", "Bank fixed deposit", "Deposits",
        listOf(
            Field("amount", "Amount (₹)", 100000.0, 1.0, 1e9),
            Field("months", "Months", 60.0, 1.0, 360.0, step = 1.0),
            rate(7.0, hint = "Your bank's rate"),
        ),
        "Interest is added every quarter, as most banks do, and paid at maturity. Enter your bank's rate: " +
            "7% is only an example.",
    ) { fd(it.getValue("amount"), it.getValue("months").toInt(), it.getValue("rate")) },
    Scheme(
        "rd", "Recurring Deposit", "Post Office or bank RD", "Deposits",
        listOf(
            Field("monthly", "Deposit each month (₹)", 5000.0, 1.0, 1e8),
            Field("months", "Months", 60.0, 1.0, 360.0, step = 1.0),
            rate(Rates.RD, hint = "Post Office 5-year RD rate"),
            balance(hint = "Optional: for an RD that's already running; then enter the months left"),
        ),
        "Starts at the Post Office 5-year RD rate. Interest is added every quarter, as the Post Office and most banks " +
            "do. Enter your bank's rate if it's different.",
    ) { rd(it.getValue("monthly"), it.getValue("months").toInt(), it.getValue("rate"), it["balance"] ?: 0.0) },
    Scheme(
        "sip", "SIP", "Mutual fund SIP", "Mutual funds",
        listOf(
            Field("monthly", "Invest each month (₹)", 10000.0, 1.0, 1e8),
            Field("years", "Years", 10.0, 1.0, 50.0, step = 1.0),
            Field("rate", "Expected return (% a year)", 12.0, 0.1, 30.0, hint = "Not guaranteed"),
            balance("Value today (₹)", "Optional: what your funds are worth now; it grows at the same return"),
        ),
        "Returns aren't guaranteed: the expected return is an assumption, not a promise. Assumes you invest at the " +
            "start of each month.",
    ) { sip(it.getValue("monthly"), it.getValue("years").toInt(), it.getValue("rate"), it["balance"] ?: 0.0) },
)

// ---------- the calculations ----------

/**
 * Rows at the end of each year, and at the last month if that isn't a whole year, starting from [start] in the
 * account. Years are numbered from when the account was opened, [yearsDone] years ago.
 */
private fun table(
    months: Int, deposited: (Int) -> Double, start: Double = 0.0, yearsDone: Int = 0, balance: (Int) -> Double,
): List<Row> {
    val ends = (12..months step 12).toMutableList()
    if (months % 12 != 0) ends += months
    var lastDeposited = 0.0
    var lastBalance = start
    return ends.map { m ->
        val d = deposited(m) - lastDeposited
        val b = balance(m)
        Row(if (m % 12 == 0) "Year ${yearsDone + m / 12}" else "Month $m", d, b - lastBalance - d, b)
            .also { lastDeposited += d; lastBalance = b }
    }
}

private fun result(invested: Double, maturity: Double, months: Int, rows: List<Row>, start: Double = 0.0) =
    Result(invested, maturity - invested - start, maturity, months, rows = rows, start = start)

/** [years] more years from [start], with a deposit at the start of each year while [depositsLeft] lasts, compounded yearly. */
private fun yearlyBalance(start: Double, deposit: Double, r: Double, years: Int, depositsLeft: Int) =
    (1..years).fold(start) { b, y -> (b + if (y <= depositsLeft) deposit else 0.0) * (1 + r) }

/** PPF for [years] from opening; [done] of them have passed and the account holds [start] today. */
fun ppf(deposit: Double, years: Int, ratePct: Double, start: Double = 0.0, done: Int = 0): Result {
    val r = ratePct / 100
    val left = years - done
    val rows = table(left * 12, { m -> m / 12 * deposit }, start, done) { m -> yearlyBalance(start, deposit, r, m / 12, left) }
    return result(deposit * left, rows.last().balance, left * 12, rows, start)
}

/** SSY matures 21 years after opening, with deposits in the first 15; [done] years have passed. */
fun ssy(deposit: Double, ratePct: Double, start: Double = 0.0, done: Int = 0): Result {
    val r = ratePct / 100
    val left = 21 - done
    val depositsLeft = maxOf(15 - done, 0)
    val rows = table(left * 12, { m -> minOf(m / 12, depositsLeft) * deposit }, start, done) { m ->
        yearlyBalance(start, deposit, r, m / 12, depositsLeft)
    }
    return result(deposit * depositsLeft, rows.last().balance, left * 12, rows, start)
}

fun nsc(amount: Double, ratePct: Double): Result {
    val rows = table(60, { amount }) { m -> amount * (1 + ratePct / 100).pow(m / 12) }
    return result(amount, rows.last().balance, 60, rows)
}

/** Months for money to double at [ratePct], compounded yearly: 115 at 7.5%. */
fun kvpMonths(ratePct: Double) = (ln(2.0) / ln(1 + ratePct / 100) * 12).roundToInt()

fun kvp(amount: Double, ratePct: Double): Result {
    val months = kvpMonths(ratePct)
    val rows = table(months, { amount }) { m -> if (m == months) 2 * amount else amount * (1 + ratePct / 100).pow(m / 12.0) }
    return result(amount, 2 * amount, months, rows)
}

/** SCSS and MIS: interest paid out [perYear] times a year for 5 years, then the deposit is returned. */
fun payout(amount: Double, ratePct: Double, perYear: Int, every: String): Result {
    val each = amount * ratePct / 100 / perYear
    val rows = (1..5).map { y -> Row("Year $y", if (y == 1) amount else 0.0, each * perYear, amount) }
    return Result(amount, each * perYear * 5, amount, 60, each, every, rows)
}

/** Compounded every quarter; months beyond the last whole quarter earn simple interest. */
fun fdValue(amount: Double, months: Int, ratePct: Double): Double {
    val r = ratePct / 100
    return amount * (1 + r / 4).pow(months / 3) * (1 + r * (months % 3) / 12)
}

fun fd(amount: Double, months: Int, ratePct: Double): Result {
    val rows = table(months, { amount }) { m -> fdValue(amount, m, ratePct) }
    return result(amount, rows.last().balance, months, rows)
}

/** Value after [months] of deposits made at the start of each month, compounded every quarter. */
fun rdValue(monthly: Double, months: Int, ratePct: Double): Double {
    val q = 1 + ratePct / 100 / 4
    return (1..months).sumOf { k -> monthly * q.pow((months - k + 1) / 3.0) }
}

/**
 * EPF until [retire]: each month 12% of [basic] (plus [vpfPct]) from you and 12% from your employer, less the
 * pension share of 8.33% (at most ₹1,250). Interest at [ratePct] on each month's closing balance, added at the end
 * of the year. [basic] rises by [raisePct] every year after the first.
 */
fun epf(
    basic: Double, age: Int, retire: Int, raisePct: Double, ratePct: Double, vpfPct: Double = 0.0, start: Double = 0.0,
): Result {
    val years = retire - age
    val r = ratePct / 1200
    var pay = basic
    var b = start
    var yours = 0.0
    var employer = 0.0
    val rows = (1..years).map { y ->
        if (y > 1) pay *= 1 + raisePct / 100
        val mine = pay * (0.12 + vpfPct / 100)
        val theirs = pay * 0.12 - minOf(pay * 0.0833, 1250.0)
        val c = mine + theirs
        // Closing balances of the 12 months: b + c, b + 2c … b + 12c.
        val interest = r * (12 * b + 78 * c)
        b += 12 * c + interest
        yours += 12 * mine
        employer += 12 * theirs
        Row("Age ${age + y}", 12 * c, interest, b)
    }
    return Result(
        yours + employer, b - start - yours - employer, b, years * 12, rows = rows, start = start,
        parts = listOf("You put in" to yours, "Your employer puts in" to employer),
    )
}

/** [months] more instalments; an RD already running holds [start] today, which keeps earning. */
fun rd(monthly: Double, months: Int, ratePct: Double, start: Double = 0.0): Result {
    val q = 1 + ratePct / 100 / 4
    val rows = table(months, { m -> m * monthly }, start) { m -> rdValue(monthly, m, ratePct) + start * q.pow(m / 3.0) }
    return result(monthly * months, rows.last().balance, months, rows, start)
}

/** [years] more of monthly investments, on top of funds worth [start] today growing at the same return. */
fun sip(monthly: Double, years: Int, ratePct: Double, start: Double = 0.0): Result {
    val i = ratePct / 100 / 12
    val value = { m: Int -> monthly * ((1 + i).pow(m) - 1) / i * (1 + i) + start * (1 + i).pow(m) }
    val rows = table(years * 12, { m -> m * monthly }, start, balance = value)
    return result(monthly * years * 12, rows.last().balance, years * 12, rows, start)
}

// ---------- showing numbers ----------

/** Rupees with Indian grouping: ₹40,68,209. */
fun rupees(x: Double): String {
    val n = x.roundToLong()
    val s = abs(n).toString()
    val grouped = if (s.length <= 3) s else s.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
    return (if (n < 0) "-₹" else "₹") + grouped
}

/** "40.68 lakh" or "1.23 crore", for amounts of a lakh or more. */
fun inWords(x: Double): String? = when {
    x >= 1e7 -> String.format(Locale.ROOT, "%.2f crore", x / 1e7)
    x >= 1e5 -> String.format(Locale.ROOT, "%.2f lakh", x / 1e5)
    else -> null
}

fun term(months: Int): String {
    val y = months / 12
    val m = months % 12
    val years = if (y == 1) "1 year" else "$y years"
    val mons = if (m == 1) "1 month" else "$m months"
    return when {
        y == 0 -> mons
        m == 0 -> years
        else -> "$years $mons"
    }
}

/** 150000.0 → "150000", 7.1 → "7.1": how a number is shown in a text field. */
fun plain(x: Double): String = if (x == x.roundToLong().toDouble()) x.roundToLong().toString() else x.toString()
