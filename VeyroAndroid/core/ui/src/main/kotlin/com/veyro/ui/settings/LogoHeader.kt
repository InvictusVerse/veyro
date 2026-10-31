package com.veyro.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val ICON_PIXELS = 256

/**
 * The Veyro launcher icon and name at the top of the settings list.
 *
 * The icon is read back from the package manager because it is declared in `:app`, which this
 * module cannot reference. If it cannot be read (previews, unusual launchers) only the name shows.
 */
@Composable
internal fun LogoHeader() {
    val context = LocalContext.current
    val launcherIcon = remember(context) { renderLauncherIcon(context) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        launcherIcon?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 24.dp, bottom = 8.dp)
                    .size(96.dp),
            )
        }
        Text(
            text = "Veyro",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        HorizontalDivider()
    }
}

/** Draws the app's launcher drawable (adaptive icons included) into a square bitmap. */
private fun renderLauncherIcon(context: Context): ImageBitmap? = try {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, ICON_PIXELS, ICON_PIXELS)
    drawable.draw(Canvas(bitmap))
    bitmap.asImageBitmap()
} catch (_: Exception) {
    null
}
