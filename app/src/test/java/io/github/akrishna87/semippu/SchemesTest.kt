package io.github.akrishna87.semippu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SchemesTest {

    // Figures published by banks, the Post Office and fund houses' calculators.

    @Test
    fun ppfMatchesThePublishedFigure() {
        // ₹1.5 lakh a year for 15 years at 7.1% is the widely quoted ₹40,68,209.
        val r = ppf(150000.0, 15, 7.1)
        assertEquals("₹40,68,209", rupees(r.maturity))
        assertEquals(2250000.0, r.invested, 0.0)
        assertEquals(15, r.rows.size)
    }

    @Test
    fun rdMatchesThePostOfficeFigure() {
        // ₹1,000 a month for 5 years at 6.7%, compounded quarterly: ₹71,366.
        assertEquals("₹71,366", rupees(rd(1000.0, 60, 6.7).maturity))
    }

    @Test
    fun sipMatchesTheUsualFigure() {
        // ₹10,000 a month for 10 years at 12%: ₹23,23,391.
        assertEquals("₹23,23,391", rupees(sip(10000.0, 10, 12.0).maturity))
    }

    @Test
    fun nscCompoundsYearlyForFiveYears() {
        assertEquals(1000 * 1.077.pow(5), nsc(1000.0, 7.7).maturity, 1e-6)
        assertEquals("₹1,449", rupees(nsc(1000.0, 7.7).maturity))
    }

    @Test
    fun kvpDoublesIn115Months() {
        assertEquals(115, kvpMonths(7.5))
        val r = kvp(50000.0, 7.5)
        assertEquals(100000.0, r.maturity, 0.0)
        assertEquals(115, r.months)
        assertEquals("Month 115", r.rows.last().label)
        assertEquals(10, r.rows.size)
    }

    @Test
    fun ssyStopsDepositsAfter15YearsAndMaturesAt21() {
        val r = ssy(150000.0, 8.2)
        assertEquals(21, r.rows.size)
        assertEquals(150000.0, r.rows[14].deposited, 0.0)
        assertEquals(0.0, r.rows[15].deposited, 0.0)
        assertEquals(2250000.0, r.invested, 0.0)
        assertEquals(r.rows[14].balance * 1.082.pow(6), r.maturity, 1e-3)
    }

    @Test
    fun scssPaysEveryQuarter() {
        val r = payout(3000000.0, 8.2, perYear = 4, every = "every quarter")
        assertEquals(61500.0, r.payout!!, 1e-9)
        assertEquals(1230000.0, r.interest, 1e-6)
        assertEquals(3000000.0, r.maturity, 0.0)
    }

    @Test
    fun misPaysEveryMonth() {
        assertEquals(5550.0, payout(900000.0, 7.4, perYear = 12, every = "every month").payout!!, 1e-9)
    }

    @Test
    fun fdCompoundsQuarterlyWithSimpleInterestForExtraMonths() {
        assertEquals(100000 * 1.0175.pow(4), fdValue(100000.0, 12, 7.0), 1e-6)
        assertEquals(100000 * 1.0175.pow(4) * (1 + 0.07 / 12), fdValue(100000.0, 13, 7.0), 1e-6)
        val r = fd(100000.0, 13, 7.0)
        assertEquals(listOf("Year 1", "Month 13"), r.rows.map { it.label })
    }

    // An account already open: carrying on from its balance must give the same answer as starting at the start.

    @Test
    fun ppfCarriesOnFromTodaysBalance() {
        val whole = ppf(150000.0, 15, 7.1)
        val after4 = whole.rows[3].balance
        val rest = ppf(150000.0, 15, 7.1, start = after4, done = 4)
        assertEquals(whole.maturity, rest.maturity, 1e-6)
        assertEquals(11, rest.rows.size)
        assertEquals("Year 5", rest.rows.first().label)
        assertEquals(150000.0 * 11, rest.invested, 0.0)
        assertEquals(after4, rest.start, 0.0)
        assertEquals(rest.maturity - after4 - rest.invested, rest.interest, 1e-6)
    }

    @Test
    fun ssyCarriesOnFromTodaysBalance() {
        val whole = ssy(150000.0, 8.2)
        assertEquals(whole.maturity, ssy(150000.0, 8.2, whole.rows[3].balance, 4).maturity, 1e-6)
        // After 17 years the deposits are over; the balance only earns interest.
        val late = ssy(150000.0, 8.2, whole.rows[16].balance, 17)
        assertEquals(0.0, late.invested, 0.0)
        assertEquals(whole.maturity, late.maturity, 1e-6)
    }

    @Test
    fun rdAndSipCarryOnFromTodaysValue() {
        assertEquals(rd(1000.0, 60, 6.7).maturity, rd(1000.0, 36, 6.7, rdValue(1000.0, 24, 6.7)).maturity, 1e-6)
        val whole = sip(10000.0, 10, 12.0)
        assertEquals(whole.maturity, sip(10000.0, 6, 12.0, whole.rows[3].balance).maturity, 1e-6)
    }

    @Test
    fun optionalFieldsCanBeBlankAndTheStartYearIsChecked() {
        val ppf = schemes.first { it.id == "ppf" }
        assertNull(ppf.fields.first { it.key == "balance" }.error(null))
        assertNull(ppf.fields.first { it.key == "since" }.error(null))
        val thisYear = java.time.Year.now().value.toDouble()
        val v = mapOf("deposit" to 150000.0, "years" to 15.0, "rate" to 7.1, "since" to thisYear - 15)
        assertEquals("since", ppf.check(v)?.first)
        assertNull(ppf.check(v + ("years" to 20.0)))
        assertEquals(11 * 12, ppf.calc(v + ("since" to thisYear - 4) + ("balance" to 500000.0)).months)
        assertEquals(15 * 12, ppf.calc(v - "since").months)
    }

    @Test
    fun epfSplitsTheEmployerShareAndAddsInterestYearly() {
        // ₹15,000 basic: you 12% = ₹1,800; employer 12% less 8.33% to EPS (₹1,249.50) = ₹550.50 a month.
        val r = epf(15000.0, 57, 58, 0.0, 8.25)
        val c = 1800.0 + 550.5
        val interest = 8.25 / 1200 * 78 * c   // on the closing balances c, 2c … 12c
        assertEquals(12 * c + interest, r.maturity, 1e-6)
        assertEquals(listOf("You put in" to 21600.0, "Your employer puts in" to 6606.0), r.parts)
        assertEquals("Age 58", r.rows.single().label)
        // Above ₹15,000 the pension share stops at ₹1,250, so more reaches EPF.
        assertEquals(12 * (3600.0 - 1250.0), epf(30000.0, 57, 58, 0.0, 8.25).parts[1].second, 1e-6)
    }

    @Test
    fun vpfIsOptionalAndOnlyAddsToYourShare() {
        val without = epf(30000.0, 30, 58, 5.0, 8.25)
        val with = epf(30000.0, 30, 58, 5.0, 8.25, vpfPct = 10.0)
        assertEquals(without.parts[1].second, with.parts[1].second, 1e-6)
        assertEquals(without.parts[0].second * 22 / 12, with.parts[0].second, 1e-6)
        assertTrue(with.maturity > without.maturity)
        val epfScheme = schemes.first { it.id == "epf" }
        assertNull(epfScheme.fields.first { it.key == "vpf" }.error(null))
        val v = epfScheme.fields.filter { !it.optional }.associate { it.key to it.default }
        assertEquals(without.maturity, epfScheme.calc(v).maturity, 1e-6)
        assertEquals("retire", epfScheme.check(v + ("age" to 58.0))?.first)
    }

    @Test
    fun epfPayRisesAndCarriesOnFromTodaysBalance() {
        val whole = epf(10000.0, 30, 58, 5.0, 8.25)
        assertEquals(whole.rows[0].deposited * 1.05, whole.rows[1].deposited, 1e-6)
        val rest = epf(10000.0 * 1.05.pow(4), 34, 58, 5.0, 8.25, start = whole.rows[3].balance)
        assertEquals(whole.maturity, rest.maturity, 1e-4)
        assertEquals("Age 35", rest.rows.first().label)
    }

    @Test
    fun everyTableAddsUpToTheResult() {
        for (s in schemes) {
            val r = s.calc(s.fields.associate { it.key to it.default })
            assertEquals(s.id, r.invested, r.rows.sumOf { it.deposited }, 1e-6)
            assertEquals(s.id, r.interest, r.rows.sumOf { it.interest }, 1e-6)
            // and the same with money already in the account
            val withBalance = s.calc(s.fields.associate { it.key to it.default } + ("balance" to 250000.0))
            assertEquals(s.id, withBalance.interest, withBalance.rows.sumOf { it.interest }, 1e-6)
            assertEquals(s.id, withBalance.maturity, withBalance.rows.last().balance, 1e-6)
            assertEquals(s.id, r.maturity, r.rows.last().balance, 1e-6)
            for (f in s.fields) assertNull("${s.id} ${f.key}", f.error(f.default))
        }
    }

    @Test
    fun fieldsCheckLimitsAndSteps() {
        val years = schemes.first { it.id == "ppf" }.fields.first { it.key == "years" }
        assertNull(years.error(20.0))
        assertNotNull(years.error(17.0))
        assertNotNull(years.error(10.0))
        assertNotNull(years.error(null))
        val scss = schemes.first { it.id == "scss" }.fields.first()
        assertNull(scss.error(15000.0))
        assertEquals("In multiples of 1000", scss.error(1500.0))
    }

    @Test
    fun rupeesUseIndianGrouping() {
        assertEquals("₹0", rupees(0.0))
        assertEquals("₹999", rupees(999.4))
        assertEquals("₹1,000", rupees(999.5))
        assertEquals("₹1,00,000", rupees(100000.0))
        assertEquals("₹12,34,56,789", rupees(123456789.0))
        assertEquals("40.68 lakh", inWords(4068209.0))
        assertEquals("1.23 crore", inWords(12345678.0))
        assertNull(inWords(99999.0))
        assertEquals("9 years 7 months", term(115))
        assertEquals("15 years", term(180))
        assertEquals("7.1", plain(7.1))
        assertEquals("150000", plain(150000.0))
    }
}
