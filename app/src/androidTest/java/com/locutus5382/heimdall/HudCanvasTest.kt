package com.locutus5382.heimdall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.DeviceSample
import com.locutus5382.heimdall.engine.HudLayout
import com.locutus5382.heimdall.engine.HudMetric
import com.locutus5382.heimdall.engine.HudProfile
import com.locutus5382.heimdall.engine.HudTelemetryHistory
import com.locutus5382.heimdall.recording.HeimdallCanvasView
import com.locutus5382.heimdall.recording.HudProfileStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Canvas drawing and in-view gesture hit-tests, without overlay permission. */
@RunWith(AndroidJUnit4::class)
class HudCanvasTest {
    private class Actions : HeimdallCanvasView.Actions {
        var stops = 0
        var cycles = 0
        var locks = 0
        var moves = 0
        var settles = 0
        override fun stop() { stops++ }
        override fun cycle() { cycles++ }
        override fun lock() { locks++ }
        override fun drag(dxPixels: Int, dyPixels: Int) { moves++ }
        override fun settle() { settles++ }
    }

    private fun render(view: HeimdallCanvasView, mode: HudLayout) {
        view.update(
            HudProfile(layout = mode),
            37L,
            DeviceSample(2000L, 42.0, 73, 39.5, 1600L, 1980L, 680L),
            listOf(
                HudTelemetryHistory.Point(1000L, 20.0, 440, 39.0),
                HudTelemetryHistory.Point(1500L, null, null, null),
                HudTelemetryHistory.Point(2000L, 42.0, 680, 39.5),
            )
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertNotEquals(Color.TRANSPARENT,
            bitmap.getPixel(view.width / 2, view.height / 2))
        bitmap.recycle()
    }

    private fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(t, t,
            MotionEvent.ACTION_DOWN, x, y, 0).also { it.recycleAfter = false })
        view.onTouchEvent(MotionEvent.obtain(t, t + 40,
            MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun allThreeLayoutsDrawWithMissingSamplesAndTouchableButtons() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val actions = Actions()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = HeimdallCanvasView(app, actions)
            for (layout in HudLayout.entries) {
                render(view, layout)
                val designW = when (layout) {
                    HudLayout.PILL -> 490f
                    HudLayout.CARD -> 388f
                    HudLayout.RIBBON -> 690f
                }
                val designH = when (layout) {
                    HudLayout.PILL -> 86f
                    HudLayout.CARD -> 275f
                    HudLayout.RIBBON -> 70f
                }
                val top = when (layout) {
                    HudLayout.PILL -> 33f
                    HudLayout.CARD -> 223f
                    HudLayout.RIBBON -> 11f
                }
                val x = (designW - 38f) * view.width / designW
                val y = (top + 24f) * view.height / designH
                val t = SystemClock.uptimeMillis()
                view.onTouchEvent(MotionEvent.obtain(
                    t, t, MotionEvent.ACTION_DOWN, x, y, 0
                ).apply { recycle() })
                view.onTouchEvent(MotionEvent.obtain(
                    t, t + 50L, MotionEvent.ACTION_UP, x, y, 0
                ).apply { recycle() })
            }
        }
        assertEquals(3, actions.stops)
    }

    @Test fun studioProfileRoundTripsAcrossAllSupportedValues() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HudProfileStore(app)
        val original = store.read()
        try {
            val chosen = HudProfile(
                HudLayout.RIBBON, .91f, .63f, locked = true,
                metrics = setOf(HudMetric.BATTERY, HudMetric.CPU_CLOCK)
            )
            store.save(chosen)
            assertEquals(chosen, store.read())
            assertEquals(HudLayout.PILL, HudLayout.RIBBON.next())
        } finally {
            store.save(original)
        }
    }
}
