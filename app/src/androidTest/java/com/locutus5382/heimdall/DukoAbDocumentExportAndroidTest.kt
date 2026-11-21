package com.locutus5382.heimdall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SAF contract boundary test. A real external document provider must still
 * be verified on Odin; this checks our platform picker contract without
 * granting storage or network permissions.
 */
@RunWith(AndroidJUnit4::class)
class DukoAbDocumentExportAndroidTest {
    @Test fun txtAndCsvUseCreateDocumentWithMimeAndSuggestedFilename() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((mime, filename) in listOf(
            "text/plain" to "DUKO_AB_12_24.txt",
            "text/csv" to "DUKO_AB_12_24.csv"
        )) {
            val contract = ActivityResultContracts.CreateDocument(mime)
            val intent = contract.createIntent(context, filename)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(mime, intent.type)
            assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
            assertEquals(filename, intent.getStringExtra(Intent.EXTRA_TITLE))
        }
    }

    @Test fun canceledPickerHasNoDestinationAndSuccessfulPickerReturnsUri() {
        val contract = ActivityResultContracts.CreateDocument("text/csv")
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
        val uri = Uri.parse("content://test.documents/document/report")
        val returned = contract.parseResult(Activity.RESULT_OK,
            Intent().setData(uri))
        assertEquals(uri, returned)
    }
}
