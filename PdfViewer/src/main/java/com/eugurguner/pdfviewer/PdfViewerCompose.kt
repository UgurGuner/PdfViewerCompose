package com.eugurguner.pdfviewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.IOException
import okio.buffer
import okio.sink
import java.io.File

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
@Keep
fun ComposePdfViewer(
    modifier: Modifier,
    pdfUrl: String,
    width: Int = 0,
    height: Int = 0,
    onPdfLoadingError: () -> Unit
) {
    val context = LocalContext.current

    val displayMetrics = context.resources.displayMetrics
    val screenWidth = if (width == 0) displayMetrics.widthPixels.dp else width.dp
    val screenHeight = if (height == 0) displayMetrics.heightPixels.dp else height.dp

    var pdfFile by remember { mutableStateOf<File?>(null) }
    val pages = remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }
    var isZooming by remember { mutableStateOf(false) }

    LaunchedEffect(pdfUrl) {
        loadPdfFile(pdfUrl)?.let { file ->
            pdfFile = file
            renderPages(pages, file, screenWidth.value.toInt(), screenHeight.value.toInt())
            isLoading.value = false
        }
    }

    var scale by remember {
        mutableFloatStateOf(1f)
    }

    val offset =
        remember {
            mutableStateOf(Offset.Zero)
        }

    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(minimumValue = 1f, maximumValue = 5f)
            val extraWidth = (scale - 1) * screenWidth.value
            val extraHeight = (scale - 1) * screenHeight.value
            val maxX = extraWidth / 2
            val maxY = extraHeight / 2
            offset.value =
                Offset(
                    x = (offset.value.x + (panChange.x * 2)).coerceIn(-maxX, maxX),
                    y = (offset.value.y + (panChange.y) * 2).coerceIn(-maxY, maxY)
                )
        }

    Column(
        modifier =
        modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading.value) {
            CircularProgressIndicator()
        } else {
            if (pages.value.isNotEmpty()) {
                LazyColumn(
                    modifier =
                    Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.value.x
                            translationY = offset.value.y
                        }
                        .transformable(state = transformState)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pointersCount = event.changes.size
                                    isZooming = pointersCount > 1 // Update zoom state
                                }
                            }
                        },
                    userScrollEnabled = !isZooming
                ) {
                    items(items = pages.value) { bitmap ->
                        BoxWithConstraints(
                            modifier =
                            Modifier
                                .fillMaxSize()
                                .aspectRatio(screenWidth / (screenHeight * 2 / 3))
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else {
                onPdfLoadingError.invoke()
            }
        }
    }

}

internal suspend fun loadPdfFile(url: String): File? {
    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()

    return try {
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                val file = File.createTempFile("pdf", ".pdf") // Create a temporary file
                val sink: BufferedSink = file.sink().buffer()
                sink.writeAll(response.body!!.source())
                sink.close()
                file
            } else {
                null // Handle unsuccessful response
            }
        }
    } catch (exception: IOException) {
        null // Handle network or file I/O exceptions
    }
}

internal suspend fun renderPages(
    pages: MutableState<List<Bitmap>>,
    file: File,
    width: Int,
    height: Int
) {
    withContext(Dispatchers.IO) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)

        val newPages =
            (0 until renderer.pageCount).map { pageIndex ->
                val page = renderer.openPage(pageIndex)
                val bitmap = Bitmap.createBitmap(width, height * 2 / 3, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap
            }

        pages.value = newPages // Update page list state

        renderer.close()
        fd.close()
    }
}