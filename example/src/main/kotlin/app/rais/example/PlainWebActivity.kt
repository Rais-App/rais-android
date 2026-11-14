package app.rais.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** A control for testing: the checkout URL in a bare WebView, with none of the SDK around it (no insets handling, no bridge). */
class PlainWebActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        WebView.setWebContentsDebuggingEnabled(true)
        setContentView(web)
        web.loadUrl(intent.getStringExtra("url").orEmpty())
    }
}
