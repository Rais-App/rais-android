package app.rais.checkout.internal.sheet

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import androidx.core.graphics.Insets
import app.rais.checkout.R
import java.io.File

/**
 * The PDF receipt, inside the checkout (the native counterpart of iOS's Quick Look): its pages, a way to share it (save it to files, print
 * it, send it), and a way back to the result.
 */
@SuppressLint("ViewConstructor")
internal class ReceiptViewer(
    context: Context,
    private val theme: SheetTheme,
    private val file: File,
    closeLabel: String,
    private val onClose: () -> Unit,
) : FrameLayout(context), Themed {
    private val pages = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val toolbar = LinearLayout(context)

    init {
        isClickable = true   // nothing under the receipt takes a tap
        elevation = theme.dp(40f)   // above the pane of the button, which floats over the page
        outlineProvider = null
        pages.orientation = LinearLayout.VERTICAL
        scroll.clipToPadding = false
        scroll.addView(pages, LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, MATCH))

        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.addView(disc(R.drawable.rais_close, closeLabel, "rais.receipt.close") { close() }, LinearLayout.LayoutParams(theme.dp(44), theme.dp(44)))
        toolbar.addView(FrameLayout(context), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(disc(R.drawable.rais_ic_share, context.getString(R.string.rais_share), "rais.receipt.share") { share() }, LinearLayout.LayoutParams(theme.dp(44), theme.dp(44)))
        addView(toolbar, LayoutParams(MATCH, WRAP, Gravity.TOP))
        render()
        applyTheme()
    }

    private fun disc(icon: Int, label: String, id: String, action: () -> Unit) = FrameLayout(context).apply {
        contentDescription = label
        tag = id
        isClickable = true
        addView(GlassPane(context, theme, null), LayoutParams(theme.dp(36), theme.dp(36), Gravity.CENTER))
        addView(Glyph(context, icon) { theme.text }, LayoutParams(theme.dp(16), theme.dp(16), Gravity.CENTER))
        setOnClickListener { action() }
    }

    fun apply(bars: Insets) {
        toolbar.setPadding(bars.left + theme.dp(10), bars.top + theme.dp(6), bars.right + theme.dp(10), 0)
        scroll.setPadding(theme.dp(12), bars.top + theme.dp(60), theme.dp(12), bars.bottom + theme.dp(16))
    }

    private fun render() {
        val width = resources.displayMetrics.widthPixels - theme.dp(24)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val height = (width.toFloat() * page.height / page.width).toInt()
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val image = ImageView(context)
                            image.setImageBitmap(bitmap)
                            image.adjustViewBounds = true
                            image.elevation = theme.dp(2f)
                            pages.addView(image, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = theme.dp(12) })
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // a receipt that cannot be drawn here can still be shared and opened elsewhere
        }
    }

    private fun share() {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".rais.receipts", file)
            val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            // nothing to share with
        } catch (_: IllegalArgumentException) {
            // the provider is missing from the merged manifest
        }
    }

    fun close() = onClose()

    override fun applyTheme() {
        setBackgroundColor(theme.surface)
    }
}

/** The SDK's own file provider (a subclass, so that it never collides with the app's own `FileProvider` in the merged manifest). */
public class RaisReceiptProvider : FileProvider()
