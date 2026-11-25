package com.locutus5382.heimdall

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locutus5382.heimdall.engine.duko.DukoVisualDocument
import com.locutus5382.heimdall.engine.duko.DukoVisualRow
import com.locutus5382.heimdall.engine.duko.DukoVisualSection
import com.locutus5382.heimdall.recording.DukoVisualRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DukoM21VisualExportAndroidTest {
    private fun snapshot(rows: Int): DukoVisualDocument =
        DukoVisualDocument(
            title = "DUKO / MULTI-RUN COMPARISON",
            subtitle = "Baseline 12, five candidates",
            sections = listOf(
                DukoVisualSection("OBSERVED COMPARISONS",
                    (1..rows).map {
                        DukoVisualRow(
                            "CPU LOAD / RUN " + it,
                            "Baseline 45.2 percent | candidate 49.9 percent",
                            "CAUTION: unverified conditions; telemetry not FPS.",
                            caution = it % 3 == 0
                        )
                    })
            ),
            caveats = listOf(
                "This report does not measure FPS or game speed.",
                "User-declared conditions do not prove matched workload.",
                "Unmeasured metrics are never interpreted as zero."
            )
        )

    @Test fun pngExportsRenderRealPixelsWithoutScreenCaptureOrNetwork() {
        val result = ByteArrayOutputStream()
        DukoVisualRenderer.write(
            result, snapshot(14), DukoVisualRenderer.Format.PNG)
        val data = result.toByteArray()
        assertTrue(data.size > 1000)
        assertEquals(0x89, data[0].toInt() and 0xff)
        assertEquals('P'.code, data[1].toInt())
        assertEquals('N'.code, data[2].toInt())
        assertEquals('G'.code, data[3].toInt())
        val image = BitmapFactory.decodeByteArray(data, 0, data.size)
        assertNotNull(image)
        try {
            assertEquals(DukoVisualRenderer.PAGE_WIDTH * 2, image.width)
            assertTrue(image.height >= DukoVisualRenderer.PAGE_HEIGHT * 2)
            assertTrue(image.getPixel(4, 4) != 0)
        } finally { image.recycle() }
    }

    @Test fun pdfExportsValidPagesIncludingLongComparison() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val output = ByteArrayOutputStream()
        DukoVisualRenderer.write(
            output, snapshot(48), DukoVisualRenderer.Format.PDF)
        val data = output.toByteArray()
        assertTrue(data.size > 1500)
        assertTrue(data.copyOfRange(0, 5).toString(Charsets.US_ASCII)
            .startsWith("%PDF-"))
        val tmp = File(app.cacheDir, "duko_m21_pdf_fixture.pdf")
        try {
            tmp.writeBytes(data)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertTrue("Long report must paginate", renderer.pageCount > 1)
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(595, 842,
                            Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue(bitmap.getPixel(0, 0) != 0)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { tmp.delete() }
    }

    @Test fun pdfAndPngUseOfflineSafDestinationContracts() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        for ((mime, name) in listOf(
            "application/pdf" to "DUKO_AB_1_2.pdf",
            "image/png" to "DUKO_MULTI_1_6runs.png"
        )) {
            val contract = ActivityResultContracts.CreateDocument(mime)
            val intent = contract.createIntent(app, name)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(mime, intent.type)
            assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
        }
    }

    @Test fun dangerouslyLongReportFailsExplicitlyRatherThanLosingRows() {
        val result = runCatching {
            DukoVisualRenderer.write(ByteArrayOutputStream(), snapshot(420),
                DukoVisualRenderer.Format.PDF)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }
}
