package io.github.akrishna87.semippu

import java.time.LocalDate
import java.time.Year
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Small savings rates notified by the Ministry of Finance every quarter. The "Quarterly rates" workflow
 * (ci/update_rates.py) reads them from India Post at the start of each quarter and updates these lines.
 */
object Rates {
    const val QUARTER = "1 Oct – 31 Dec 2026"
    /** The last day these rates apply to; after it the app says a newer quarter has started. */
    const val QUARTER_END = "2026-12-31"
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
    /** What happens to [maturity] at the end (NPS: the lump sum, the annuity and the pension it pays). */
    val after: List<Pair<String, Double>> = emptyList(),
    /** Something to know about this result in particular. */
    val note: String? = null,
    /** What [maturity] is called in the summary. */
    val title: String = "Maturity value",
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

/** True once the quarter in [Rates] is over, so the government may have notified newer rates. */
fun ratesOutOfDate(today: LocalDate = LocalDate.now()) = today.isAfter(LocalDate.parse(Rates.QUARTER_END))

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
        "Doubles your money; at the current rate that takes ${kvpMonths(Rates.KVP)} months (${term(kvpMonths(Rates.KVP))}). From ₹1,000, in multiples of ₹100. " +
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
        "epf", "EPF + VPF", "Employees' and Voluntary Provident Fund", "Retirement",
        listOf(
            Field("basic", "Basic pay + DA a month (₹)", 30000.0, 1.0, 1e8),
            Field("age", "Your age", 30.0, 18.0, 59.0, step = 1.0),
            Field("retire", "Retire at", 58.0, 19.0, 60.0, step = 1.0, hint = "EPF is paid out at 58"),
            Field("raise", "Pay rise each year (%)", 5.0, 0.0, 30.0),
            Field("rate", "Interest rate (% a year)", Rates.EPF, 0.1, 30.0, hint = "EPFO's rate for ${Rates.EPF_YEAR}"),
            Field(
                "vpf", "VPF (% of basic + DA)", 0.0, 0.0, 88.0,
                hint = "Optional: extra you choose to put in, on top of your 12%", optional = true,
            ),
            balance(hint = "Optional: from your EPF passbook"),
        ),
        "EPF: you put in 12% of basic + DA, and your employer 12%: 3.67% goes to your EPF and 8.33% to the " +
            "pension scheme (EPS), at most ₹1,250 a month.\n\n" +
            "VPF (Voluntary Provident Fund): extra you choose to put into the same account through your employer, " +
            "up to all of your basic + DA. It earns the same rate as EPF, and your employer doesn't add to it. " +
            "Ask your payroll team to start it; it's usually fixed for the financial year.\n\n" +
            "Interest is worked out on each month's balance and added once a year. Interest on more than ₹2.5 lakh " +
            "a year of your own contributions (EPF and VPF together) is taxable. Your contributions count towards " +
            "80C in the old tax regime. The EPS pension isn't included here.",
        check = { v -> if (v.getValue("retire") <= v.getValue("age")) "retire" to "Later than your age" else null },
    ) {
        epf(
            it.getValue("basic"), it.getValue("age").toInt(), it.getValue("retire").toInt(), it.getValue("raise"),
            it.getValue("rate"), it["vpf"] ?: 0.0, it["balance"] ?: 0.0,
        )
    },
    Scheme(
        "nps1", "NPS Tier I", "National Pension System: the pension account", "Retirement",
        listOf(
            Field("monthly", "You put in each month (₹)", 5000.0, 1.0, 1e8, hint = "At least ₹1,000 a year"),
            Field(
                "employer", "Your employer puts in each month (₹)", 0.0, 0.0, 1e8,
                hint = "Optional: corporate NPS", optional = true,
            ),
            Field("age", "Your age", 30.0, 18.0, 84.0, step = 1.0, hint = "You can join from 18 to 85"),
            Field("exit", "Exit at", 60.0, 19.0, 85.0, step = 1.0, hint = "60 is the usual exit; you can stay in until 85"),
            Field("stepup", "Increase each year (%)", 0.0, 0.0, 50.0, hint = "How much more you put in each year"),
            Field(
                "rate", "Expected return (% a year)", 10.0, 0.1, 30.0,
                hint = "Not guaranteed: depends on your equity share (up to 75%)",
            ),
            Field(
                "annuity", "Kept for a pension (%)", 20.0, 20.0, 100.0,
                hint = "At least 20% (40% for government employees)",
            ),
            Field("annuityRate", "Annuity rate (% a year)", 6.0, 1.0, 15.0, hint = "Insurers' rates vary; check theirs"),
            balance("Value today (₹)", "Optional: from your NPS statement"),
            since(67),
        ),
        "Tier I is the pension account, and the one you must open first.\n\n" +
            "• Money in: at least ₹500 to open and ₹1,000 a year, with no upper limit. Unlimited contributions until 85.\n" +
            "• Locked in until 60 (or 15 years, if earlier). Before then you can take out up to 25% of your own " +
            "contributions, up to 4 times, 4 years apart, for things like children's education or marriage, a house " +
            "or illness.\n" +
            "• At exit: up to 80% as a lump sum (government employees: 60%) and at least 20% (40%) buys an annuity " +
            "that pays a pension for life. With ₹8 lakh or less you can take it all; from ₹8 lakh to ₹12 lakh, up to " +
            "₹6 lakh as a lump sum and the rest over at least 6 years or as an annuity.\n" +
            "• Leaving early (before 60 and before 15 years): at least 80% must buy an annuity, unless the total is " +
            "₹5 lakh or less.\n" +
            "• Tax: in the old regime your contributions count towards 80C (up to ₹1.5 lakh) and up to ₹50,000 more on " +
            "top (80CCD(1B)). Your employer's contribution is deductible in both regimes: up to 14% of basic + DA in " +
            "the new regime, 10% in the old. At exit 60% of the total is tax-free; any lump sum above that is taxed " +
            "at your slab, and so is the pension.\n" +
            "• Choice: Auto (life-cycle funds that cut equity as you age) or Active (you set it: up to 75% equity, " +
            "with corporate bonds and government securities). You can change it 4 times a year.\n\n" +
            "Returns aren't guaranteed: the expected return is an assumption. Contributions are taken at the start " +
            "of each month and grow every month.",
        check = { v ->
            when {
                v.getValue("exit") <= v.getValue("age") -> "exit" to "Later than your age"
                done(v) > v.getValue("age") - 18 -> "since" to "You'd have been under 18"
                else -> null
            }
        },
    ) {
        nps(
            it.getValue("monthly"), it["employer"] ?: 0.0, it.getValue("age").toInt(), it.getValue("exit").toInt(),
            it.getValue("stepup"), it.getValue("rate"), it.getValue("annuity"), it.getValue("annuityRate"),
            it["balance"] ?: 0.0, done(it),
        )
    },
    Scheme(
        "nps2", "NPS Tier II", "National Pension System: the savings account", "Retirement",
        listOf(
            Field("monthly", "Invest each month (₹)", 5000.0, 1.0, 1e8, hint = "At least ₹250 each time"),
            Field("years", "Years", 10.0, 1.0, 50.0, step = 1.0),
            Field("rate", "Expected return (% a year)", 9.0, 0.1, 30.0, hint = "Not guaranteed"),
            balance("Value today (₹)", "Optional: from your NPS statement"),
        ),
        "Tier II is an optional savings account, opened alongside an active Tier I.\n\n" +
            "• Money in: at least ₹1,000 to open and ₹250 after that, with no yearly minimum and no upper limit.\n" +
            "• No lock-in: take out any amount at any time, with no annuity to buy. You can also move money from " +
            "Tier II into Tier I (but not back).\n" +
            "• Tax: no deduction for what you put in, and gains are taxable. Central government employees can use " +
            "the Tier II Tax Saver scheme instead: it counts towards 80C in the old regime, with a 3-year lock-in.\n" +
            "• It invests in the same funds as Tier I, at the same low charges, and you can choose a different fund " +
            "manager and mix for it.\n" +
            "• Closing Tier I closes Tier II too, and its balance is paid to your bank.\n\n" +
            "Tier I vs Tier II: Tier I is for retirement, with tax benefits and money locked until 60. Tier II is " +
            "for flexible savings, with no tax benefits and no lock-in.\n\n" +
            "Returns aren't guaranteed: the expected return is an assumption. Contributions are taken at the start " +
            "of each month and grow every month.",
    ) { sip(it.getValue("monthly"), it.getValue("years").toInt(), it.getValue("rate"), it["balance"] ?: 0.0)
        .copy(note = "No lock-in: you can take out any of this whenever you want.") },
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
    Scheme(
        "lumpsum", "Lumpsum", "Mutual fund lumpsum", "Mutual funds",
        listOf(
            Field("amount", "Invest once (₹)", 100000.0, 1.0, 1e10),
            Field("years", "Years", 10.0, 1.0, 50.0, step = 1.0),
            Field("rate", "Expected return (% a year)", 12.0, 0.1, 30.0, hint = "Not guaranteed"),
        ),
        "One investment, left to grow. Returns aren't guaranteed: the expected return is an assumption, not a " +
            "promise. It's compounded once a year, as fund houses' lumpsum calculators do. Equity fund gains above " +
            "₹1.25 lakh in a year are taxed at 12.5% when you sell after a year.",
    ) { lumpsum(it.getValue("amount"), it.getValue("years").toInt(), it.getValue("rate")) },
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
 * EPF until [retire]: each month 12% of [basic] (plus [vpfPct] as VPF) from you and 12% from your employer, less
 * the pension share of 8.33% (at most ₹1,250). Interest at [ratePct] on each month's closing balance, added at the
 * end of the year. [basic] rises by [raisePct] every year after the first.
 */
fun epf(
    basic: Double, age: Int, retire: Int, raisePct: Double, ratePct: Double, vpfPct: Double = 0.0, start: Double = 0.0,
): Result {
    val years = retire - age
    val r = ratePct / 1200
    var pay = basic
    var b = start
    var yours = 0.0
    var vpf = 0.0
    var employer = 0.0
    var taxableFrom: Int? = null
    val rows = (1..years).map { y ->
        if (y > 1) pay *= 1 + raisePct / 100
        val mine = pay * 0.12
        val extra = pay * vpfPct / 100
        val theirs = pay * 0.12 - minOf(pay * 0.0833, 1250.0)
        val c = mine + extra + theirs
        // Closing balances of the 12 months: b + c, b + 2c … b + 12c.
        val interest = r * (12 * b + 78 * c)
        b += 12 * c + interest
        yours += 12 * mine
        vpf += 12 * extra
        employer += 12 * theirs
        if (taxableFrom == null && 12 * (mine + extra) > 250000) taxableFrom = age + y - 1
        Row("Age ${age + y}", 12 * c, interest, b)
    }
    val parts = if (vpf > 0) {
        listOf("Your EPF (12%)" to yours, "Your VPF" to vpf, "Your employer puts in" to employer)
    } else {
        listOf("You put in" to yours, "Your employer puts in" to employer)
    }
    val note = taxableFrom?.let {
        "From age $it you put in more than ₹2.5 lakh a year: the interest on the part above that is taxable."
    }
    return Result(
        yours + vpf + employer, b - start - yours - vpf - employer, b, years * 12, rows = rows, start = start,
        parts = parts, note = note,
    )
}

/**
 * NPS Tier I from [age] to [exit]: [monthly] from you and [employer] from your employer at the start of each month,
 * both rising by [stepUpPct] a year, growing at [ratePct] a year compounded monthly, on top of [start] today. The
 * account was opened [done] years ago. At exit [annuityPct] of it buys an annuity paying [annuityRatePct] a year, and
 * the rest can be taken as a lump sum, under the rules for non-government subscribers.
 */
fun nps(
    monthly: Double, employer: Double, age: Int, exit: Int, stepUpPct: Double, ratePct: Double,
    annuityPct: Double, annuityRatePct: Double, start: Double = 0.0, done: Int = 0,
): Result {
    val i = ratePct / 1200
    var mine = monthly
    var theirs = employer
    var b = start
    var yours = 0.0
    var employers = 0.0
    val rows = (1..exit - age).map { y ->
        if (y > 1) { mine *= 1 + stepUpPct / 100; theirs *= 1 + stepUpPct / 100 }
        val before = b
        repeat(12) { b = (b + mine + theirs) * (1 + i) }
        yours += 12 * mine
        employers += 12 * theirs
        Row("Age ${age + y}", 12 * (mine + theirs), b - before - 12 * (mine + theirs), b)
    }
    val years = exit - age + done
    val early = exit < 60 && years < 15
    // Before 60 and 15 years, at least 80% buys an annuity; otherwise at least 20%.
    val share = if (early) maxOf(annuityPct, 80.0) else annuityPct
    val allLumpSum = if (early) b <= 500000 else b <= 800000
    val annuity = if (allLumpSum) 0.0 else b * share / 100
    val lumpSum = b - annuity
    val pension = annuity * annuityRatePct / 100 / 12
    val note = when {
        allLumpSum && early -> "₹5 lakh or less when you leave early: you can take all of it as a lump sum."
        allLumpSum -> "₹8 lakh or less at exit: you can take all of it as a lump sum, with no annuity to buy."
        early -> "Leaving before 60 and before 15 years in NPS: at least 80% must buy an annuity, so " +
            "${plain(share)}% does here."
        b <= 1200000 -> "From ₹8 lakh to ₹12 lakh you can also take up to ₹6 lakh as a lump sum and the rest " +
            "over at least 6 years, instead of buying an annuity."
        else -> null
    }
    val taxFree = minOf(lumpSum, b * 0.6)
    val after = buildList {
        add("Lump sum you can take" to lumpSum)
        if (lumpSum > taxFree + 0.5) add("Of which tax-free (60% of the total)" to taxFree)
        if (annuity > 0) {
            add("Buys an annuity (${plain(share)}%)" to annuity)
            add("Pension a month, at ${plain(annuityRatePct)}%" to pension)
        }
    }
    return Result(
        yours + employers, b - start - yours - employers, b, (exit - age) * 12, rows = rows, start = start,
        parts = if (employers > 0) listOf("You put in" to yours, "Your employer puts in" to employers) else emptyList(),
        after = after, note = note, title = "Your NPS at exit",
    )
}

/** One investment of [amount], growing at [ratePct] a year, compounded yearly. */
fun lumpsum(amount: Double, years: Int, ratePct: Double): Result {
    val rows = table(years * 12, { amount }) { m -> amount * (1 + ratePct / 100).pow(m / 12) }
    return result(amount, rows.last().balance, years * 12, rows)
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
