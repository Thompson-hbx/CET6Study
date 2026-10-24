package com.example.cet6

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.cet6.data.PaperDocuments
import com.example.cet6.ui.theme.CET6Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** In-app, offline source viewer. It opens only bundled PDFs; no external storage permission. */
class PaperPdfActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("document").orEmpty()
        if (!PaperDocuments.validFilename(name) || name !in assets.list("personal-papers").orEmpty()) {
            finish()
            return
        }
        setContent {
            CET6Theme {
                PdfScreen(name)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PdfScreen(name: String) {
        var file by remember { mutableStateOf<File?>(null) }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var pages by remember { mutableIntStateOf(0) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var error by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var zoom by rememberSaveable { mutableFloatStateOf(1f) }
        LaunchedEffect(name) {
            try {
                file = withContext(Dispatchers.IO) {
                    val folder = File(cacheDir, "paper-pdfs").apply { mkdirs() }
                    File(folder, name).also { target ->
                        assets.open("personal-papers/$name").use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true; loading = false }
        }
        LaunchedEffect(file, page) {
            val source = file ?: return@LaunchedEffect
            loading = true
            bitmap = null
            error = false
            try {
                val rendered = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdfPage ->
                                val scale = minOf(1600f / pdfPage.width, 2400f / pdfPage.height)
                                val result = Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1),
                                    (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                result.eraseColor(Color.WHITE)
                                pdfPage.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                result to renderer.pageCount
                            }
                        }
                    }
                }
                bitmap = rendered.first
                pages = rendered.second
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { loading = false }
        }
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (name.endsWith("_ans.pdf")) "参考资料" else "试卷原文") },
                navigationIcon = { TextButton(onClick = { finish() }) { Text("返回") } }) },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { page--; zoom = 1f }, enabled = !loading && page > 0) { Text("上一页") }
                            Text("${page + 1} / ${pages.takeIf { it > 0 } ?: "—"}", Modifier.padding(16.dp))
                            TextButton(onClick = { page++; zoom = 1f }, enabled = !loading && page + 1 < pages) { Text("下一页") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("缩小") }
                            Text("${(zoom * 100).toInt()}%", Modifier.padding(16.dp))
                            TextButton(onClick = { zoom = (zoom + .5f).coerceAtMost(3f) }, enabled = zoom < 3f) { Text("放大") }
                        }
                    }
                }
            }
        ) { inset ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(inset)) {
                val width = maxWidth
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Text("暂时无法打开这份 PDF，请返回后重试。", Modifier.padding(24.dp))
                bitmap?.let { rendered ->
                    key(page) {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                            Image(rendered.asImageBitmap(), contentDescription = "原始资料第 ${page + 1} 页",
                                modifier = Modifier.width(width * zoom).aspectRatio(rendered.width.toFloat() / rendered.height))
                        }
                    }
                }
            }
        }
    }
}
