package one.rarebit.cruciform.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import one.rarebit.cruciform.domain.RecoveryBackup
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Prints the recovery sheet ([RecoverySheet]) through the system print dialog.
 *
 * The sheet is drawn onto a `PdfDocument` page sized in points (1/72 inch), so the
 * millimetre sizes are exact at 100%, and that PDF is written ONLY to the print
 * framework's output descriptor: no cache file, no share intent, nothing of ours on
 * disk holds the secret. (What the print service then does with the job, including a
 * "Save as PDF" target, is outside this app: the backup screen warns against it.)
 */
internal object RecoverySheetPrinter {
    private const val JOB_NAME = "Cruciform recovery sheet"
    private const val DOCUMENT_NAME = "recovery-sheet.pdf"
    private const val MILS_PER_INCH = 1000f
    private const val PT_PER_INCH = 72f

    /** Open the print dialog for [backup]'s sheet, from the activity behind [context]. */
    fun print(context: Context, backup: RecoveryBackup) {
        // PrintManager.print() refuses anything but an Activity context.
        val activity = context.activity() ?: return
        val manager = activity.getSystemService(PrintManager::class.java) ?: return
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()
        val content = RecoverySheet.forBackup(backup, dateLabel(System.currentTimeMillis()))
        manager.print(JOB_NAME, SheetAdapter(content), attributes)
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    /** The sheet's date, as the Go sheet prints it: "2 January 2006". */
    fun dateLabel(millis: Long): String = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date(millis))

    /** A portrait page for [media] in whole points; A4 when the printer names none. */
    private fun pageSize(media: PrintAttributes.MediaSize?): Pair<Int, Int> {
        val portrait = media?.asPortrait() ?: return RecoverySheet.A4_WIDTH_PT to RecoverySheet.A4_HEIGHT_PT
        fun pt(mils: Int) = (mils / MILS_PER_INCH * PT_PER_INCH).roundToInt()
        return pt(portrait.widthMils) to pt(portrait.heightMils)
    }

    /** One black [Paint] per text style, and the matching [RecoverySheet.Measure]. */
    private class Paints {
        private val byStyle = mutableMapOf<RecoverySheet.TextStyle, Paint>()

        val fill = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        val measure = RecoverySheet.Measure { text, style -> of(style).measureText(text) }

        fun of(textStyle: RecoverySheet.TextStyle): Paint = byStyle.getOrPut(textStyle) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = textStyle.sizePt
                letterSpacing = textStyle.letterSpacingEm
                typeface = when (textStyle.font) {
                    RecoverySheet.Font.SERIF -> Typeface.SERIF
                    RecoverySheet.Font.SERIF_BOLD -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    RecoverySheet.Font.MONO -> Typeface.MONOSPACE
                    RecoverySheet.Font.MONO_BOLD -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
            }
        }
    }

    private fun draw(canvas: Canvas, page: RecoverySheet.Page, qr: RecoverySheet.QrModules, paints: Paints) {
        for (op in page.ops) {
            when (op) {
                is RecoverySheet.Op.Text -> canvas.drawText(op.text, op.x, op.baseline, paints.of(op.style))
                is RecoverySheet.Op.Box -> canvas.drawRect(op.x, op.y, op.x + op.width, op.y + op.height, paints.fill)
                is RecoverySheet.Op.Qr -> drawQr(canvas, op, qr, paints.fill)
            }
        }
    }

    /** Each row's dark modules as runs, so adjacent modules print as one solid bar. */
    private fun drawQr(canvas: Canvas, op: RecoverySheet.Op.Qr, qr: RecoverySheet.QrModules, fill: Paint) {
        val module = op.size / qr.size
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                if (!qr[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < qr.size && qr[x, y]) x++
                val top = op.y + y * module
                canvas.drawRect(op.x + start * module, top, op.x + x * module, top + module, fill)
            }
        }
    }

    /**
     * Writes the one-page sheet for the size the print dialog chose. [content] is
     * dropped when the job finishes.
     */
    private class SheetAdapter(private var content: RecoverySheet.Content?) : PrintDocumentAdapter() {
        private var width = RecoverySheet.A4_WIDTH_PT
        private var height = RecoverySheet.A4_HEIGHT_PT

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val (w, h) = pageSize(newAttributes.mediaSize)
            val changed = oldAttributes == null || w != width || h != height
            width = w
            height = h
            val info = PrintDocumentInfo.Builder(DOCUMENT_NAME)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, changed)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback,
        ) {
            val sheet = content
            if (sheet == null) {
                callback.onWriteFailed("The recovery sheet is no longer available.")
                return
            }
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
                val paints = Paints()
                val layout = RecoverySheet.layout(sheet, paints.measure, width.toFloat(), height.toFloat())
                draw(page.canvas, layout, RecoverySheet.encodeQr(sheet.payload), paints)
                document.finishPage(page)
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                // Straight into the print framework's descriptor: the only place the PDF goes.
                // (A FileOutputStream over a borrowed descriptor does not own it; the
                // framework closes it.)
                val out = FileOutputStream(destination.fileDescriptor)
                document.writeTo(out)
                out.flush()
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: IOException) {
                callback.onWriteFailed(e.message)
            } finally {
                document.close()
            }
        }

        override fun onFinish() {
            content = null
        }
    }
}
