package app.gyro.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyMeterTest {

    private fun sample(ms: Long, amps: Double, volts: Double = 100.0, speed: Double = 36.0, odo: Long? = null, charging: Boolean? = null) =
        Telemetry(timestampMs = ms, voltageV = volts, batteryCurrentA = amps, speedKmh = speed, totalDistanceM = odo, charging = charging)

    @Test
    fun `integrates energy out and regeneration in`() {
        val m = EnergyMeter()
        // 10 minutes at 1000 W (10 A × 100 V), sampled every second → 166.7 Wh out.
        for (s in 0..600) m.add(sample(s * 1000L, 10.0))
        // Then 2 minutes of −500 W braking downhill → 16.7 Wh back.
        for (s in 601..720) m.add(sample(s * 1000L, -5.0))
        val st = m.stats
        assertEquals(1000.0 * 600 / 3600 + 0.5 * (1000 - 500) / 3600, st.whOut, 0.5)
        assertEquals(500.0 * 119 / 3600, st.whRegen, 0.5)
        assertEquals(st.whOut - st.whRegen, st.whNet, EPS)
        assertEquals(1000.0, st.peakPowerW, EPS)
        assertEquals(500.0, st.peakRegenW, EPS)
        assertTrue(st.regenPercent!! in 9.0..11.0)
        assertTrue(st.ahOut > st.ahIn)
    }

    @Test
    fun `ignores connection gaps`() {
        val m = EnergyMeter(maxGapMs = 2_000)
        m.add(sample(0, 10.0))
        m.add(sample(1_000, 10.0))
        m.add(sample(60_000, 10.0)) // 59 s gap
        assertEquals(1000.0 / 3600, m.stats.whOut, 1e-6)
    }

    @Test
    fun `charging while parked is not counted as regeneration`() {
        val m = EnergyMeter()
        for (s in 0..60) m.add(sample(s * 1000L, -4.0, speed = 0.0, charging = true))
        assertEquals(0.0, m.stats.whRegen, EPS)
        assertEquals(400.0 * 60 / 3600, m.stats.whCharged, 1e-6)
    }

    @Test
    fun `distance prefers odometer and survives glitches`() {
        val m = EnergyMeter()
        m.add(sample(0, 5.0, speed = 36.0, odo = 10_000))
        m.add(sample(1_000, 5.0, speed = 36.0, odo = 10_010))
        m.add(sample(2_000, 5.0, speed = 36.0, odo = 99_999_999)) // glitch → falls back to speed (10 m)
        assertEquals(0.020, m.stats.distanceKm, 1e-9)
        assertNull("too short for Wh/km", m.stats.whPerKm)
    }
}

class RangeEstimatorTest {

    @Test
    fun `uses default until enough riding then follows recent consumption`() {
        val r = RangeEstimator(windowKm = 3.0, defaultWhPerKm = 20.0, reservePercent = 10.0)
        val early = r.estimate(capacityWh = 2000.0, batteryPercent = 60.0)!!
        assertFalse(early.basedOnRiding)
        assertEquals(2000 * 0.5 / 20.0, early.km, EPS)

        // Ride 5 km at 30 Wh/km.
        for (i in 0..250) r.add(EnergyStats(whOut = i * 0.02 * 30, distanceKm = i * 0.02))
        val later = r.estimate(capacityWh = 2000.0, batteryPercent = 60.0)!!
        assertTrue(later.basedOnRiding)
        assertEquals(30.0, later.whPerKm, 0.5)
        assertEquals(1000.0 / 30.0, later.km, 1.0)
    }

    @Test
    fun `no estimate without capacity`() {
        assertNull(RangeEstimator().estimate(null, 50.0))
    }
}

class TiltbackPredictorTest {

    private fun t(ms: Long, pwm: Double?, speed: Double = 30.0) = Telemetry(timestampMs = ms, pwmPercent = pwm, speedKmh = speed)

    @Test
    fun `pwm thresholds`() {
        val p = TiltbackPredictor()
        assertEquals(TiltbackPredictor.Level.NORMAL, p.assess(t(0, 40.0), null).level)
        assertEquals(TiltbackPredictor.Level.CAUTION, TiltbackPredictor().assess(t(0, 72.0), null).level)
        assertEquals(TiltbackPredictor.Level.WARNING, TiltbackPredictor().assess(t(0, 82.0), null).level)
        assertEquals(TiltbackPredictor.Level.CRITICAL, TiltbackPredictor().assess(t(0, 91.0), null).level)
    }

    @Test
    fun `rising pwm warns early`() {
        val p = TiltbackPredictor()
        var last: TiltbackPredictor.Assessment? = null
        // PWM climbing 40 %/s: at 65 % it projects past 90 % within 1.5 s.
        for (i in 0..10) last = p.assess(t(i * 100L, 25.0 + i * 4.0), null)
        assertEquals(65.0, last!!.pwmPercent!!, EPS)
        assertEquals(TiltbackPredictor.Level.WARNING, last.level)
        assertEquals(TiltbackPredictor.Reason.PWM_TREND, last.reason)
    }

    @Test
    fun `speed close to tiltback`() {
        val a = TiltbackPredictor().assess(t(0, null, speed = 43.0), tiltbackSpeedKmh = 45.0)
        assertEquals(TiltbackPredictor.Level.WARNING, a.level)
        assertEquals(TiltbackPredictor.Reason.SPEED, a.reason)
    }
}

class AlarmEngineTest {

    private val speedRule = AlarmRule(id = 1, metric = AlarmMetric.SPEED, threshold = 40.0, repeatSeconds = 5, hysteresis = 2.0)

    @Test
    fun `fires on crossing, repeats, clears with hysteresis`() {
        val e = AlarmEngine()
        fun at(ms: Long, speed: Double) = e.evaluate(listOf(speedRule), Telemetry(speedKmh = speed), ms)
        assertTrue(at(0, 39.0).isEmpty())
        assertTrue(at(100, 40.5).single().firstTrigger)
        assertTrue(at(1_000, 41.0).isEmpty())
        assertFalse(at(5_200, 41.0).single().firstTrigger) // repeat after 5 s
        assertTrue(at(5_300, 39.0).isEmpty())              // inside hysteresis: still active, no event
        assertTrue(at(5_400, 40.2).isEmpty())              // no new first trigger
        assertTrue(at(5_500, 37.5).isEmpty())              // cleared
        assertTrue(at(5_600, 40.0).single().firstTrigger)
    }

    @Test
    fun `active set follows the condition`() {
        val e = AlarmEngine()
        val rules = listOf(speedRule)
        e.evaluate(rules, Telemetry(speedKmh = 45.0), 0)
        assertEquals(setOf(1L), e.activeRuleIds)
        e.evaluate(rules, Telemetry(speedKmh = 30.0), 100)
        assertTrue(e.activeRuleIds.isEmpty())
        e.evaluate(rules, Telemetry(speedKmh = 45.0), 200)
        e.evaluate(listOf(speedRule.copy(enabled = false)), Telemetry(speedKmh = 45.0), 300)
        assertTrue("disabling a rule clears it", e.activeRuleIds.isEmpty())
    }

    @Test
    fun `battery low fires once`() {
        val rule = AlarmEngine.DEFAULT_RULES.first { it.metric == AlarmMetric.BATTERY }
        val e = AlarmEngine()
        assertEquals(1, e.evaluate(listOf(rule), Telemetry(batteryPercent = 29.0), 0).size)
        assertTrue(e.evaluate(listOf(rule), Telemetry(batteryPercent = 28.0), 600_000).isEmpty())
    }

    @Test
    fun `disabled rules and missing metrics are skipped`() {
        val e = AlarmEngine()
        assertTrue(e.evaluate(listOf(speedRule.copy(enabled = false)), Telemetry(speedKmh = 50.0), 0).isEmpty())
        val pwm = AlarmRule(id = 2, metric = AlarmMetric.PWM, threshold = 80.0)
        assertTrue(e.evaluate(listOf(pwm), Telemetry(pwmPercent = null), 0).isEmpty())
    }
}

class BatteryEstimatorTest {

    @Test
    fun `cell curve`() {
        assertEquals(100.0, BatteryEstimator.percentFromCellVoltage(4.2), EPS)
        assertEquals(0.0, BatteryEstimator.percentFromCellVoltage(3.1), EPS)
        assertEquals((3.8 - 3.325) / 0.0085, BatteryEstimator.percentFromCellVoltage(3.8), EPS)
        assertTrue(BatteryEstimator.percentFromCellVoltage(3.3) in 0.0..8.9)
    }

    @Test
    fun `series guess`() {
        assertEquals(16, BatteryEstimator.guessCells(67.2))
        assertEquals(20, BatteryEstimator.guessCells(84.0))
        assertEquals(24, BatteryEstimator.guessCells(100.8))
        assertEquals(30, BatteryEstimator.guessCells(126.0))
        assertEquals(36, BatteryEstimator.guessCells(151.2))
        assertEquals(42, BatteryEstimator.guessCells(176.4))
        assertNull(BatteryEstimator.guessCells(12.0))
        assertEquals(32, BatteryEstimator.snapCells(31.8))
        assertNull(BatteryEstimator.snapCells(27.0))
    }
}

class SettingsWriteGuardTest {

    private val backup = FactoryBackup("w1", mapOf(WheelParam.TILTBACK_SPEED to 45, WheelParam.PEDALS_MODE to 1), 0)
    private val writable = setOf(WheelParam.TILTBACK_SPEED, WheelParam.PEDALS_MODE)
    private val current = mapOf(WheelParam.TILTBACK_SPEED to 45, WheelParam.PEDALS_MODE to 1)

    @Test
    fun `diff lists every change`() {
        val r = SettingsWriteGuard.review("w1", current, mapOf(WheelParam.TILTBACK_SPEED to 50), writable, backup, 80.0)
        assertEquals(listOf(SettingChange(WheelParam.TILTBACK_SPEED, 45, 50)), r.changes)
        assertTrue(r.canProceed)
        assertEquals(1, r.warnings.size) // 50 is above the original 45
    }

    @Test
    fun `blocks without backup and on low battery`() {
        val noBackup = SettingsWriteGuard.review("w1", current, mapOf(WheelParam.PEDALS_MODE to 2), writable, null, 80.0)
        assertTrue(noBackup.blockers.contains(WriteBlocker.NoFactoryBackup))
        val lowBattery = SettingsWriteGuard.review("w1", current, mapOf(WheelParam.PEDALS_MODE to 2), writable, backup, 49.0)
        assertTrue(lowBattery.blockers.any { it is WriteBlocker.LowBattery })
        val unknownBattery = SettingsWriteGuard.review("w1", current, mapOf(WheelParam.PEDALS_MODE to 2), writable, backup, null)
        assertTrue(unknownBattery.blockers.any { it is WriteBlocker.LowBattery })
        val otherWheel = SettingsWriteGuard.review("w2", current, mapOf(WheelParam.PEDALS_MODE to 2), writable, backup, 90.0)
        assertTrue(otherWheel.blockers.contains(WriteBlocker.NoFactoryBackup))
    }

    @Test(expected = SettingsRejectedException::class)
    fun `unacknowledged above-stock warning refuses permit`() {
        val r = SettingsWriteGuard.review("w1", current, mapOf(WheelParam.TILTBACK_SPEED to 50), writable, backup, 80.0)
        SettingsWriteGuard.authorize(r, r.changes.toSet(), acknowledgedWarnings = emptySet())
    }

    @Test(expected = SettingsRejectedException::class)
    fun `every change must be confirmed`() {
        val r = SettingsWriteGuard.review(
            "w1", current, mapOf(WheelParam.TILTBACK_SPEED to 40, WheelParam.PEDALS_MODE to 2), writable, backup, 80.0,
        )
        SettingsWriteGuard.authorize(r, setOf(r.changes.first()), emptySet())
    }

    @Test
    fun `restore review writes backup values`() {
        val changed = mapOf(WheelParam.TILTBACK_SPEED to 50, WheelParam.PEDALS_MODE to 2)
        val r = SettingsWriteGuard.restoreReview("w1", changed, writable, backup, 70.0)
        assertEquals(setOf(SettingChange(WheelParam.TILTBACK_SPEED, 50, 45), SettingChange(WheelParam.PEDALS_MODE, 2, 1)), r.changes.toSet())
        assertTrue(r.warnings.isEmpty())
        val permit = SettingsWriteGuard.authorize(r, r.changes.toSet(), emptySet())
        assertEquals(45, permit.target[WheelParam.TILTBACK_SPEED])
    }
}
