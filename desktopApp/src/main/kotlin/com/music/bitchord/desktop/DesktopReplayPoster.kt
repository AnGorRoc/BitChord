package com.music.bitchord.desktop

import java.awt.Color
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
import kotlin.math.roundToInt

/**
 * Renders a high-resolution 1080x1920 shareable poster of the listener's Replay stats.
 */
object DesktopReplayPoster {

    const val POSTER_WIDTH = 1080
    const val POSTER_HEIGHT = 1920

    fun renderPoster(
        summary: DesktopReplaySummary,
        holder: String,
        memberSince: String?,
    ): BufferedImage {
        val image = BufferedImage(POSTER_WIDTH, POSTER_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // Deep background gradient
            val gradient = GradientPaint(
                0f, 0f, Color(18, 14, 30),
                0f, POSTER_HEIGHT.toFloat(), Color(8, 7, 14),
            )
            g.paint = gradient
            g.fillRect(0, 0, POSTER_WIDTH, POSTER_HEIGHT)

            // Ambient background accents
            g.color = Color(110, 40, 220, 45)
            g.fillOval(-150, -100, 650, 650)
            g.color = Color(220, 50, 80, 35)
            g.fillOval(POSTER_WIDTH - 450, 500, 700, 700)
            g.color = Color(30, 140, 240, 30)
            g.fillOval(-100, POSTER_HEIGHT - 600, 600, 600)

            var y = 140

            // Brand Header
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 32)
            g.color = Color(255, 255, 255, 180)
            g.drawString("BITCHORD", 90, y)

            g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
            g.color = Color(255, 255, 255, 120)
            val memberText = if (!memberSince.isNullOrBlank()) "MEMBER SINCE $memberSince" else "REPLAY"
            g.drawString(memberText, POSTER_WIDTH - 90 - g.fontMetrics.stringWidth(memberText), y)

            y += 80
            // Main Title
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 76)
            g.color = Color.WHITE
            val periodTitle = summary.label.ifBlank { summary.period.chip }
            g.drawString(periodTitle, 90, y)

            if (holder.isNotBlank()) {
                y += 50
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 26)
                g.color = Color(255, 255, 255, 160)
                g.drawString("Created for $holder", 90, y)
            }

            y += 80

            // Big Stat Cards
            val cardY = y
            val cardW = 425
            val cardH = 150

            // Minutes Card
            drawStatCard(
                g = g,
                x = 90,
                y = cardY,
                w = cardW,
                h = cardH,
                value = String.format("%,d", summary.minutes),
                label = "MINUTES LISTENED",
                detail = "${summary.totalPlays} total plays",
            )

            // Songs Card
            drawStatCard(
                g = g,
                x = POSTER_WIDTH - 90 - cardW,
                y = cardY,
                w = cardW,
                h = cardH,
                value = summary.distinctSongs.toString(),
                label = "DIFFERENT SONGS",
                detail = "${summary.distinctArtists} different artists",
            )

            y += cardH + 70

            // Top Songs Section
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            g.color = Color(255, 255, 255, 210)
            g.drawString("TOP SONGS", 90, y)
            y += 45

            val topSongs = summary.songs.take(5)
            topSongs.forEachIndexed { idx, entry ->
                drawRow(
                    g = g,
                    index = idx + 1,
                    title = entry.song.title,
                    subtitle = entry.song.artist,
                    badge = "${entry.plays} plays",
                    x = 90,
                    y = y,
                    width = POSTER_WIDTH - 180,
                )
                y += 65
            }

            y += 45

            // Top Artists Section
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            g.color = Color(255, 255, 255, 210)
            g.drawString("TOP ARTISTS", 90, y)
            y += 45

            val topArtists = summary.artists.take(5)
            topArtists.forEachIndexed { idx, entry ->
                val minutes = entry.ms / 60_000L
                drawRow(
                    g = g,
                    index = idx + 1,
                    title = entry.title,
                    subtitle = if (minutes > 0) "$minutes mins" else null,
                    badge = "${entry.plays} plays",
                    x = 90,
                    y = y,
                    width = POSTER_WIDTH - 180,
                )
                y += 65
            }

            // Top Genres / Habits preview if space permits
            if (summary.genres.isNotEmpty()) {
                y += 40
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
                g.color = Color(255, 255, 255, 210)
                g.drawString("TOP GENRES", 90, y)
                y += 45
                val genreNames = summary.genres.take(4).joinToString("  •  ") { it.title }
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
                g.color = Color(255, 255, 255, 180)
                g.drawString(genreNames, 90, y)
            }

            // Footer
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 20)
            g.color = Color(255, 255, 255, 100)
            val footer = "bitchord.app • Your music on your own device"
            g.drawString(footer, (POSTER_WIDTH - g.fontMetrics.stringWidth(footer)) / 2, POSTER_HEIGHT - 70)

        } finally {
            g.dispose()
        }
        return image
    }

    private fun drawStatCard(
        g: Graphics2D,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        value: String,
        label: String,
        detail: String,
    ) {
        g.color = Color(255, 255, 255, 16)
        g.fillRoundRect(x, y, w, h, 28, 28)
        g.color = Color(255, 255, 255, 30)
        g.drawRoundRect(x, y, w, h, 28, 28)

        g.font = Font(Font.SANS_SERIF, Font.BOLD, 48)
        g.color = Color.WHITE
        g.drawString(value, x + 28, y + 60)

        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.color = Color(255, 255, 255, 180)
        g.drawString(label, x + 28, y + 95)

        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        g.color = Color(255, 255, 255, 130)
        g.drawString(detail, x + 28, y + 122)
    }

    private fun drawRow(
        g: Graphics2D,
        index: Int,
        title: String,
        subtitle: String?,
        badge: String,
        x: Int,
        y: Int,
        width: Int,
    ) {
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.color = Color(255, 255, 255, 140)
        g.drawString(index.toString(), x, y + 20)

        val textX = x + 45
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.color = Color.WHITE
        val maxTitleW = width - 260
        val clippedTitle = clipText(g, title, maxTitleW)
        g.drawString(clippedTitle, textX, y + 10)

        if (subtitle != null) {
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
            g.color = Color(255, 255, 255, 140)
            val clippedSub = clipText(g, subtitle, maxTitleW)
            g.drawString(clippedSub, textX, y + 32)
        }

        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.color = Color(255, 255, 255, 160)
        val badgeW = g.fontMetrics.stringWidth(badge)
        g.drawString(badge, x + width - badgeW, y + 20)
    }

    private fun clipText(g: Graphics2D, text: String, maxWidth: Int): String {
        if (g.fontMetrics.stringWidth(text) <= maxWidth) return text
        var end = text.length - 1
        while (end > 0 && g.fontMetrics.stringWidth(text.substring(0, end) + "...") > maxWidth) {
            end--
        }
        return if (end > 0) text.substring(0, end) + "..." else text
    }

    fun copyToClipboard(image: BufferedImage) {
        val transferable = ImageTransferable(image)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }

    fun saveToFile(image: BufferedImage, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        ImageIO.write(image, "PNG", targetFile)
    }

    fun promptSaveFile(parent: Frame? = null, suggestedName: String = "bitchord-replay.png"): File? {
        val dialog = FileDialog(parent, "Save Replay Poster", FileDialog.SAVE).apply {
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
