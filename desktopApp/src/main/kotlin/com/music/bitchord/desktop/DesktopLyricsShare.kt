package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.ui.player.LyricsShareRequest
import java.awt.FileDialog
import java.awt.Font
import java.awt.Frame
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DesktopLyricsShareRenderer {

    const val CARD_WIDTH = 1080
    const val MAX_CARD_HEIGHT = 1920

    suspend fun renderCard(
        request: LyricsShareRequest,
        story: Boolean = false,
    ): BufferedImage = withContext(Dispatchers.IO) {
        val nonGapLines = request.lines.filterNot { it.isGap }
        val lineCount = nonGapLines.size.coerceAtLeast(1)

        val targetHeight = if (story) MAX_CARD_HEIGHT else (650 + lineCount * 90).coerceAtMost(MAX_CARD_HEIGHT)
        val image = BufferedImage(CARD_WIDTH, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // Dark rich background
            val gradient = GradientPaint(
                0f, 0f, java.awt.Color(24, 18, 38),
                0f, targetHeight.toFloat(), java.awt.Color(12, 10, 18),
            )
            g.paint = gradient
            g.fillRect(0, 0, CARD_WIDTH, targetHeight)

            // Glow accent
            g.color = java.awt.Color(130, 60, 240, 45)
            g.fillOval(-100, -50, 500, 500)
            g.color = java.awt.Color(240, 70, 100, 35)
            g.fillOval(CARD_WIDTH - 400, targetHeight - 400, 500, 500)

            // Header brand
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
            g.color = java.awt.Color(255, 255, 255, 160)
            g.drawString("BITCHORD", CARD_WIDTH - 240, 90)

            // Content positioning
            var y = if (story) (targetHeight - (lineCount * 95)) / 2 else 200

            // Lyrics Lines
            request.lines.forEach { line ->
                if (line.isGap) {
                    y += 35
                } else {
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, 46)
                    g.color = java.awt.Color.WHITE
                    drawWrappedText(g, line.text, 90, y, CARD_WIDTH - 180)
                    y += 68

                    if (!line.subText.isNullOrBlank()) {
                        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 28)
                        g.color = java.awt.Color(255, 255, 255, 160)
                        drawWrappedText(g, line.subText!!, 90, y, CARD_WIDTH - 180)
                        y += 50
                    }
                    y += 24
                }
            }

            // Song & Artist footer
            val footerY = targetHeight - 120
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 30)
            g.color = java.awt.Color.WHITE
            g.drawString(request.song.title, 90, footerY)

            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
            g.color = java.awt.Color(255, 255, 255, 160)
            g.drawString(request.song.artist, 90, footerY + 36)

        } finally {
            g.dispose()
        }
        image
    }

    private fun drawWrappedText(g: Graphics2D, text: String, x: Int, y: Int, maxWidth: Int) {
        val words = text.split(" ")
        var line = ""
        var currentY = y
        for (word in words) {
            val testLine = if (line.isEmpty()) word else "$line $word"
            if (g.fontMetrics.stringWidth(testLine) > maxWidth && line.isNotEmpty()) {
                g.drawString(line, x, currentY)
                line = word
                currentY += g.fontMetrics.height
            } else {
                line = testLine
            }
        }
        if (line.isNotEmpty()) {
            g.drawString(line, x, currentY)
        }
    }

    fun copyToClipboard(image: BufferedImage) {
        val transferable = ImageTransferable(image)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }

    fun saveToFile(image: BufferedImage, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        ImageIO.write(image, "PNG", targetFile)
    }

    fun promptSaveFile(parent: Frame? = null, suggestedName: String = "bitchord-lyrics.png"): File? {
        val dialog = FileDialog(parent, "Save Lyrics Card", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val filename = dialog.file ?: return null
        val file = File(dir, filename)
        return if (file.name.endsWith(".png", ignoreCase = true)) file else File(dir, "$filename.png")
    }

    private class ImageTransferable(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}

@Composable
fun DesktopLyricsShareDialog(
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
) {
    var renderedImage by remember { mutableStateOf<BufferedImage?>(null) }
    var copiedNotice by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        renderedImage = DesktopLyricsShareRenderer.renderCard(request, story = false)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxHeight(0.92f)
                    .width(440.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E1B2E))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Share Lyrics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Card Preview
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (renderedImage != null) {
                        Image(
                            bitmap = renderedImage!!.toComposeImageBitmap(),
                            contentDescription = "Lyrics Card",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("Rendering...", color = Color.White.copy(alpha = 0.5f))
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Action Buttons
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            renderedImage?.let { img ->
                                DesktopLyricsShareRenderer.copyToClipboard(img)
                                copiedNotice = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (copiedNotice) "Copied!" else "Copy")
                    }

                    Button(
                        onClick = {
                            renderedImage?.let { img ->
                                val target = DesktopLyricsShareRenderer.promptSaveFile(suggestedName = "lyrics-${request.song.title.take(20)}.png")
                                if (target != null) {
                                    DesktopLyricsShareRenderer.saveToFile(img, target)
                                    onDismiss()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6E56CF)),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save PNG")
                    }
                }
            }
        }
    }
}
