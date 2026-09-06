package com.immineal.hdri360.test.suites

import com.immineal.hdri360.core.capture.CaptureTier
import com.immineal.hdri360.core.capture.Lens
import com.immineal.hdri360.core.capture.LensChooser
import com.immineal.hdri360.core.capture.SensorSize
import com.immineal.hdri360.test.TestCase
import com.immineal.hdri360.test.TestKit

/**
 * Which lenses a phone really has, and which one to start on.
 *
 * The fixture is not invented. These are the numbers a Pixel 9a reports, read
 * off the device: `cameraIdList` gives 0 and 1, camera 0 says
 * LOGICAL_MULTI_CAMERA and lists physical ids 2 and 3, and those two are a
 * 4.53 mm 70.5 degree module and a 1.84 mm **104.2 degree** module - both FULL,
 * both with RAW and a manual sensor. The wide lens the app never offered is
 * sitting behind the one it did, and it is not a lesser camera.
 *
 * What that lens is worth is measured rather than argued: 21 capture directions
 * against 34, with every frame holding six partners at a quarter overlap instead
 * of four. Fewer frames and better overlap at the same time.
 */
class LensSuite : TestCase {
    override fun name(): String = "lenses"

    /** A Pixel 9a, as it describes itself. */
    private fun pixel9a(): List<Lens> = listOf(
        Lens("0", "0", null, false, 4.53, 70.5, 55.9,
            CaptureTier.LINEAR_RAW, SensorSize(4000, 3000)),
        Lens("0:2", "0", "2", false, 4.53, 70.5, 55.9,
            CaptureTier.LINEAR_RAW, SensorSize(4000, 3000)),
        Lens("0:3", "0", "3", false, 1.84, 104.2, 87.2,
            CaptureTier.LINEAR_RAW, SensorSize(4208, 3120)),
        Lens("1", "1", null, true, 2.74, 81.0, 65.0,
            CaptureTier.LINEAR_RAW, SensorSize(4208, 3072)))

    override fun run(t: TestKit) {
        everyLensIsFound(t)
        aLensWithoutRawIsStillOffered(t)
        theDefaultIsOneTheCameraWillActuallyDeliver(t)
        whatChoosingCostsIsMeasured(t)
        nothingSensibleFromNothing(t)
    }

    /**
     * Decision 5: every camera is enumerated, including the ones behind a logical
     * one.
     *
     * And the awkward half of it: a logical camera and one of its own physicals
     * are frequently the same lens. On the 9a, physical 2 has the focal length,
     * field of view and sensor of camera 0 itself, because camera 0 *is* the main
     * module with the ultrawide bolted on behind it. Offering both would put two
     * identical rows in front of the person, one of which needs a physical stream
     * binding for no reason at all.
     */
    private fun everyLensIsFound(t: TestKit) {
        val offered = LensChooser.offer(pixel9a())

        t.eq(3L, offered.size.toLong(),
            "three real lenses on this phone: main, ultrawide, front")
        val ids = offered.map { it.id }
        t.check(ids.contains("0:3"), "the ultrawide behind camera 0 is offered at last")
        t.check(ids.contains("0"), "so is the main camera")
        t.check(ids.contains("1"), "and the front one")
        t.check(!ids.contains("0:2"),
            "but not physical 2, which is camera 0 under another name")

        // The one that survived the duplicate is the one that needs no physical
        // binding: fewer moving parts on the camera path for the same pixels.
        val main = offered.first { Math.abs(it.focalLengthMm - 4.53) < 0.01 }
        t.check(main.physicalId == null, "the main lens opens as itself, not as a stream binding")
        t.eq("0", main.openId, "on the camera the system actually lists")

        // The ultrawide can only be reached through its parent.
        val wide = offered.first { it.id == "0:3" }
        t.eq("3", wide.physicalId, "the ultrawide is a physical stream")
        t.eq("0", wide.openId, "on the logical camera that hides it")
        t.check(wide.isPhysical, "and says so, because the camera path has to treat it differently")

        // Back before front. A sphere shot on a selfie camera is a thing somebody
        // might want and never the thing they meant to pick.
        t.check(!offered[0].frontFacing && !offered[1].frontFacing,
            "the back lenses come first")
        t.check(offered[2].frontFacing, "and the front one last")
    }

    /**
     * Decision 6: a lens without RAW is still offered, with its tier stated.
     *
     * The person chooses with the trade-off in front of them rather than being
     * protected from it. What must not happen is a lens quietly disappearing
     * because it cannot produce a measurement - on a phone whose only wide module
     * has no RAW, that would be the app hiding the reason a sphere takes twice as
     * long.
     */
    private fun aLensWithoutRawIsStillOffered(t: TestKit) {
        val lenses = listOf(
            Lens("0", "0", null, false, 4.53, 70.5, 55.9,
                CaptureTier.LINEAR_RAW, SensorSize(4000, 3000)),
            // Wider, and only YUV. Exactly the case the decision is about.
            Lens("0:3", "0", "3", false, 1.84, 104.2, 87.2,
                CaptureTier.MANUAL_YUV, SensorSize(4208, 3120)),
            Lens("0:4", "0", "4", false, 6.90, 40.0, 30.0,
                CaptureTier.LOCKED_AUTO, SensorSize(3000, 2250)))
        val offered = LensChooser.offer(lenses)
        t.eq(3L, offered.size.toLong(), "no lens is hidden for being a lesser camera")

        for (l in offered) {
            val said = LensChooser.describe(l)
            t.check(said.isNotEmpty(), "every lens can say what it is")
            // Checked on what it says rather than on the absence of a substring:
            // "no RAW" contains "RAW", and a test that cannot tell those apart
            // would pass on a description that claimed the opposite.
            when (l.tier) {
                CaptureTier.LINEAR_RAW ->
                    t.check(said.endsWith("RAW"), "a RAW lens ends by saying RAW")
                CaptureTier.MANUAL_YUV ->
                    t.check(said.contains("no RAW"),
                        "a lens that cannot measure radiance says so in as many words")
                CaptureTier.LOCKED_AUTO ->
                    t.check(said.contains("camera picks"),
                        "and one the camera drives says who is choosing the exposure")
            }
        }

        // The tier is carried, not inferred later from the label.
        t.check(!offered.first { it.id == "0:3" }.measuresRadiance,
            "a YUV lens is not a radiance measurement, and the lens itself knows")
        t.check(offered.first { it.id == "0" }.measuresRadiance,
            "while the RAW one is")
    }

    /**
     * Decision 7, corrected by the phone: the default is the widest lens the
     * camera will actually deliver RAW from, and a lens that opens as itself
     * beats a wider one that only exists as a physical stream.
     *
     * The width argument was right and is untouched - the 9a's ultrawide takes
     * the sphere from 34 directions to 21 and raises the worst frame's partner
     * count from four to six. What was missing is that a field of view nobody
     * can capture is worth nothing at all.
     *
     * Measured, twice, in a room: opened as `0:3` - a RAW stream bound to a
     * physical sub-camera of the logical camera - the ultrawide delivered **two
     * frames out of eighty-four in seventeen seconds**, and the capture died
     * with every one of its twenty-one directions abandoned. The same phone on
     * camera `0`, opening as itself, had already produced a full thirty-four
     * direction bundle in one go, 137 frames. Preview, metering and the whole
     * scan work on the physical binding; it is RAW_SENSOR that does not arrive.
     *
     * So this is a preference and not a prohibition. The ultrawide is still
     * offered, still describable, still choosable - and if a phone turns out to
     * deliver through it, nothing here stops that. It just is not what the app
     * starts on, because the first capture a person takes should be one that
     * finishes.
     */
    private fun theDefaultIsOneTheCameraWillActuallyDeliver(t: TestKit) {
        val offered = LensChooser.offer(pixel9a())
        val pick = LensChooser.default(offered)
        if (pick == null) { t.fail("a phone with cameras has a default"); return }
        t.eq("0", pick.id, "the app starts on the lens that opens as itself")
        t.check(!pick.isPhysical, "not on a stream bound to a physical sub-camera")
        t.check(!pick.frontFacing, "and it faces away from the person")
        t.check(pick.measuresRadiance, "and it is a radiance measurement")

        // The ultrawide has not been taken away, only demoted.
        t.check(offered.any { it.id == "0:3" },
            "the ultrawide is still offered - the person may pick it")

        // Offered, but not silently. Somebody picking it should be told what
        // they are picking, because the failure it produced was not a message -
        // it was four minutes of standing in a room holding a phone still.
        val wideSaid = LensChooser.describe(offered.first { it.id == "0:3" })
        t.check(wideSaid.contains("untested") || wideSaid.contains("may not"),
            "the description of a physical stream says it is not the safe choice: " + wideSaid)
        t.check(wideSaid.contains("104"), "while still saying what the lens is: " + wideSaid)
        val mainSaid = LensChooser.describe(offered.first { it.id == "0" })
        t.check(!mainSaid.contains("untested") && !mainSaid.contains("may not"),
            "and a lens that opens as itself carries no such warning: " + mainSaid)
        t.lessThan(pick.horizontalFovDeg,
            offered.first { it.id == "0:3" }.horizontalFovDeg,
            "and it really is the wider lens that was passed over")

        // Among lenses that all open as themselves, width decides as before.
        val twoWhole = listOf(
            Lens("0", "0", null, false, 4.53, 70.5, 55.9,
                CaptureTier.LINEAR_RAW, SensorSize(4000, 3000)),
            Lens("5", "5", null, false, 1.84, 104.2, 87.2,
                CaptureTier.LINEAR_RAW, SensorSize(4208, 3120)))
        t.eq("5", LensChooser.default(LensChooser.offer(twoWhole))?.id,
            "a phone that lists its ultrawide as a camera of its own starts on it")

        // Width alone is not enough: a wider lens that cannot measure radiance
        // does not displace a RAW one. Linear data is the product.
        val withWiderYuv = pixel9a() + Lens("0:9", "0", "9", false, 1.20, 130.0, 110.0,
            CaptureTier.MANUAL_YUV, SensorSize(4000, 3000))
        t.eq("0", LensChooser.default(LensChooser.offer(withWiderYuv))?.id,
            "a wider lens without RAW does not become the default, it is only offered")

        // With nothing that can shoot RAW, the same order holds: deliverable
        // first, then width. The argument never depended on RAW.
        val noRaw = pixel9a().map {
            Lens(it.id, it.openId, it.physicalId, it.frontFacing, it.focalLengthMm,
                it.horizontalFovDeg, it.verticalFovDeg, CaptureTier.MANUAL_YUV, it.sensor)
        }
        t.eq("0", LensChooser.default(LensChooser.offer(noRaw))?.id,
            "on a phone with no RAW at all, the whole camera still wins over a binding")

        // A phone whose only back lens is physical still has to start somewhere:
        // demoting is not refusing.
        val onlyPhysical = listOf(pixel9a()[2], pixel9a()[3])
        t.eq("0:3", LensChooser.default(LensChooser.offer(onlyPhysical))?.id,
            "a phone with nothing but a physical back lens starts on it rather than on nothing")

        // A phone with only a front camera has to start somewhere.
        val frontOnly = listOf(pixel9a()[3])
        t.eq("1", LensChooser.default(LensChooser.offer(frontOnly))?.id,
            "a phone with only a front camera starts on it rather than on nothing")
    }

    /**
     * What the choice costs, in the unit the person is spending: directions.
     *
     * A field of view in degrees means nothing to somebody deciding whether to
     * use a lens. How many times they have to stop, aim and hold still does, and
     * it is the number this whole decision turns on.
     */
    private fun whatChoosingCostsIsMeasured(t: TestKit) {
        val offered = LensChooser.offer(pixel9a())
        val main = offered.first { it.id == "0" }
        val wide = offered.first { it.id == "0:3" }

        val mainDirs = LensChooser.directionsFor(main)
        val wideDirs = LensChooser.directionsFor(wide)
        t.eq(34L, mainDirs.toLong(), "the main lens needs 34 directions, as the phone has shot")
        t.eq(21L, wideDirs.toLong(), "the ultrawide needs 21")
        t.lessThan(wideDirs.toDouble(), mainDirs.toDouble(), "which is the point of offering it")

        // Said out loud, because a row that shows only degrees makes the person
        // do this arithmetic themselves.
        t.check(LensChooser.describe(wide).contains("21"),
            "and the lens says how many, not just how wide")

        // A lens held sideways is a different plan: the roll swaps which field of
        // view sets the ring spacing and which sets the azimuth spacing.
        t.greaterThan(LensChooser.directionsFor(wide, 0.0).toDouble(), 0.0,
            "an unrolled plan is still a plan")
    }

    private fun nothingSensibleFromNothing(t: TestKit) {
        t.eq(0L, LensChooser.offer(emptyList()).size.toLong(), "no cameras, no lenses")
        t.check(LensChooser.default(emptyList()) == null,
            "and no default, rather than a fabricated one")
    }
}
