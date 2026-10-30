package com.veyro.mirror

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.veyro.VeyroApplication
import com.veyro.core.common.VeyroLog
import com.veyro.core.designsystem.theme.VeyroTheme
import com.veyro.core.link.SpeakerCaptureRules
import com.veyro.core.media.DocumentScan
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

/**
 * Takes a photo or scans a page for a PC (ADR-073). The phone's own camera app takes the picture
 * (best quality, no camera code here); a photo is then checked and sent, a scan gets four corner
 * handles, a flattened page and an optional black-and-white "Enhance document". Started only by
 * Veyro for a request that is still waiting. The image goes to the PC as a transfer; the copies
 * in the cache are deleted afterwards.
 */
public class CaptureActivity : ComponentActivity() {

    internal companion object {
        private const val EXTRA_RID = "rid"
        private const val STATE_STARTED = "started"

        fun intent(context: Context, rid: String): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_RID, rid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private val host: SpeakerAndCapture get() = (application as VeyroApplication).container.speakerAndCapture
    private val dispatchers get() = (application as VeyroApplication).container.dispatchers

    private var rid: String = ""
    private var scan = false
    private var started = false
    private var finished = false
    private var photo by mutableStateOf<Bitmap?>(null)
    private var busy by mutableStateOf(false)

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) {
            done(null, "cancelled")
            return@registerForActivityResult
        }
        busy = true
        lifecycleScope.launch {
            val loaded = withContext(dispatchers.default) { DocumentScan.loadUpright(host.captureFile(rid, "")) }
            busy = false
            if (loaded == null) done(null, "error") else photo = loaded
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else done(null, "permission")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rid = intent.getStringExtra(EXTRA_RID).orEmpty()
        val request = host.pendingCapture(rid) ?: run {
            finish()
            return
        }
        scan = request.mode == SpeakerCaptureRules.MODE_SCAN
        started = savedInstanceState?.getBoolean(STATE_STARTED) == true
        setContent {
            VeyroTheme {
                CaptureScreen(
                    pcName = request.pcName,
                    scan = scan,
                    photo = photo,
                    busy = busy,
                    onRetake = { photo = null; launchCamera() },
                    onCancel = { done(null, "cancelled") },
                    onSend = { corners, enhance -> send(corners, enhance) },
                )
            }
        }
        if (started && savedInstanceState != null && host.captureFile(rid, "").isFile) {
            // Recreated (rotation, or memory) after the camera app returned: show the photo again.
            busy = true
            lifecycleScope.launch {
                photo = withContext(dispatchers.default) { DocumentScan.loadUpright(host.captureFile(rid, "")) }
                busy = false
            }
        }
        if (!started) {
            started = true
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STARTED, started)
    }

    private fun launchCamera() {
        val file = host.captureFile(rid, "")
        file.delete()
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.clips", file)
        try {
            takePicture.launch(uri)
        } catch (e: RuntimeException) {
            VeyroLog.w("Capture") { "No camera app: ${e.javaClass.simpleName}" }
            done(null, "error")
        }
    }

    private fun send(corners: List<PointF>?, enhance: Boolean) {
        val source = photo ?: return
        busy = true
        lifecycleScope.launch {
            val out = host.captureFile(rid, "-out")
            val ok = withContext(dispatchers.default) {
                runCatching {
                    var image = source
                    if (scan && corners != null) image = DocumentScan.flatten(image, corners)
                    if (scan && enhance) image = DocumentScan.enhance(image)
                    DocumentScan.saveJpeg(image, out, quality = if (scan && enhance) 85 else 92)
                }.isSuccess
            }
            busy = false
            if (ok) done(out, "error") else done(null, "error")
        }
    }

    private fun done(file: java.io.File?, reason: String) {
        if (finished) return
        finished = true
        host.finishCapture(rid, file, reason)
        // The original stays until the sent copy is read; both go with the next capture.
        host.cleanCaptures(keep = rid)
        finish()
    }

    override fun onDestroy() {
        // Closed with Back before sending: the PC hears "cancelled".
        if (isFinishing && !finished) done(null, "cancelled")
        super.onDestroy()
    }
}

@Composable
private fun CaptureScreen(
    pcName: String,
    scan: Boolean,
    photo: Bitmap?,
    busy: Boolean,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onSend: (List<PointF>?, Boolean) -> Unit,
) {
    var enhance by remember { mutableStateOf(true) }
    val corners = remember(photo) {
        mutableStateListOf<PointF>().apply { photo?.let { addAll(DocumentScan.defaultCorners(it.width, it.height)) } }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (scan) "Scan for $pcName" else "Photo for $pcName",
            style = MaterialTheme.typography.titleLarge,
        )
        if (scan && photo != null) {
            Text("Drag the corners to the page's edges.", style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator()
                photo == null -> Text("Opening the camera…")
                scan -> CornerEditor(photo, corners)
                else -> Image(photo.asImageBitmap(), contentDescription = "The photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (scan && photo != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = enhance, onCheckedChange = { enhance = it })
                Text("Enhance document (crisp black-and-white text)")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            if (photo != null) {
                OutlinedButton(onClick = onRetake, enabled = !busy) { Text("Retake") }
                Button(onClick = { onSend(if (scan) corners.toList() else null, enhance) }, enabled = !busy) {
                    Text(if (scan) "Send scan" else "Use photo")
                }
            }
        }
    }
}

/** The photo with four draggable corners (image pixels in [corners]: TL, TR, BR, BL). */
@Composable
private fun CornerEditor(photo: Bitmap, corners: MutableList<PointF>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = min(boxW / photo.width, boxH / photo.height)
        val left = (boxW - photo.width * scale) / 2
        val top = (boxH - photo.height * scale) / 2
        fun toView(p: PointF) = Offset(left + p.x * scale, top + p.y * scale)
        var dragged by remember { mutableStateOf(-1) }
        Image(photo.asImageBitmap(), contentDescription = "The page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(photo, scale) {
                detectDragGestures(
                    onDragStart = { at ->
                        dragged = corners.indices.minByOrNull { i -> toView(corners[i]).let { hypot(it.x - at.x, it.y - at.y) } } ?: -1
                    },
                    onDragEnd = { dragged = -1 },
                    onDragCancel = { dragged = -1 },
                    onDrag = { change, amount ->
                        change.consume()
                        val i = dragged
                        if (i < 0) return@detectDragGestures
                        val p = corners[i]
                        corners[i] = PointF(
                            (p.x + amount.x / scale).coerceIn(0f, photo.width.toFloat()),
                            (p.y + amount.y / scale).coerceIn(0f, photo.height.toFloat()),
                        )
                    },
                )
            },
        ) {
            val points = corners.map(::toView)
            if (points.size == 4) {
                val outline = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (k in 1..3) lineTo(points[k].x, points[k].y)
                    close()
                }
                drawPath(outline, Color(0xFF4C8DF6), style = Stroke(width = 3.dp.toPx()))
                points.forEach {
                    drawCircle(Color.White, radius = 14.dp.toPx(), center = it)
                    drawCircle(Color(0xFF2F5BEA), radius = 10.dp.toPx(), center = it)
                }
            }
        }
    }
}
