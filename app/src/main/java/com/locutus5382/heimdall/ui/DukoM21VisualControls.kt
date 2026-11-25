package com.locutus5382.heimdall.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locutus5382.heimdall.recording.DukoVisualRenderer
import com.locutus5382.heimdall.ui.theme.BifrostColors

/** One SAF picker per format. Only a frozen comparison snapshot is written. */
@Composable
internal fun DukoM21VisualControls(
    allowed: Boolean,
    onPrepare: (DukoVisualRenderer.Format) -> String?,
    onResult: (DukoVisualRenderer.Format, Uri?) -> Unit,
    status: String?,
) {
    val pdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> onResult(DukoVisualRenderer.Format.PDF, uri) }
    val png = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> onResult(DukoVisualRenderer.Format.PNG, uri) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = allowed,
            onClick = {
                onPrepare(DukoVisualRenderer.Format.PDF)?.let(pdf::launch)
            }
        ) { Text("SAVE PDF", fontSize = 12.sp) }
        OutlinedButton(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = allowed,
            onClick = {
                onPrepare(DukoVisualRenderer.Format.PNG)?.let(png::launch)
            }
        ) { Text("SAVE PNG", fontSize = 12.sp) }
    }
    if (status != null) Text(status, color = BifrostColors.Muted, fontSize = 11.sp)
}
