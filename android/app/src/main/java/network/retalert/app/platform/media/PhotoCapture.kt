package network.retalert.app.platform.media

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** CameraX photo capture surface. Binds a [Preview] + [ImageCapture] use case to the
 *  compose-local lifecycle, renders the live preview into a [PreviewView], and on the
 *  shutter press captures a JPEG [ByteArray] (rotation-corrected) via [onCaptured].
 *
 *  Used by the Send screen to attach a photo to an outgoing alert (High-tier media). */
@Composable
fun PhotoCaptureView(
    onCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(ctx)
        // ProcessCameraProvider.getInstance returns a ListenableFuture; await it off the
        // main thread, then bind use cases to the compose-local lifecycle owner.
        val resolved = withContext(Dispatchers.IO) { provider.get() }
        resolved.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        resolved.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bytes = image.toJpegByteArray()
                                onCaptured(bytes)
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                            // Surface errors back to the UI as a no-op capture.
                        }
                    },
                )
            }) { Text("Capture") }
        }
    }
}

/** Convert an [ImageProxy] to a rotation-corrected JPEG [ByteArray]. */
private fun ImageProxy.toJpegByteArray(): ByteArray {
    val bitmap = toBitmap()
    val rotated = if (imageInfo.rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else bitmap
    val out = ByteArrayOutputStream()
    rotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}