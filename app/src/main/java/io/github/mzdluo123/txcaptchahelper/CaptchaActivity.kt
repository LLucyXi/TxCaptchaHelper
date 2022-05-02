package io.github.mzdluo123.txcaptchahelper

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonParser
import kotlinx.android.synthetic.main.activity_captcha.*
import okhttp3.*
import java.io.IOException
import java.time.LocalDate
import java.util.*


class CaptchaActivity : AppCompatActivity() {
    companion object {
        const val RESULT_OK = 0
        val TAG = CaptchaActivity::class.java.name
    }

    private val okhttp = lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captcha)
        initWebView()
        webview.loadUrl(intent.getStringExtra("url"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return onJsBridgeInvoke(request!!.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                return onJsBridgeInvoke(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                finishCallback()
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webview.setOnTouchListener { view, motionEvent ->

                Log.d(TAG, motionEvent.toString())
                Log.d(TAG, "touch ${motionEvent.x} ${motionEvent.y} ${motionEvent.action}")


            return@setOnTouchListener false
        }
    }


    private fun onJsBridgeInvoke(request: Uri): Boolean {
        if (request.path.equals("/onVerifyCAPTCHA")) {
            val p = request.getQueryParameter("p")
            val jsData = JsonParser.parseString(p).asJsonObject
            authFinish(jsData["ticket"].asString)
        }
        return false
    }

    private fun authFinish(ticket: String) {
        val intent = Intent().putExtra("ticket", ticket)
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun finishCallback() {
        webview.evaluateJavascript(
            """
                ${'$'}(document).bind("touchend", function(e){console.log(e)});
            document.getElementById("slideBg").src
        """.trimMargin()
        ) {
            val url = it.slice(1..it.length - 2)
            Log.d(TAG, url)
            if (!url.startsWith("http")) {
                Log.w(TAG, "IMG URL EMPTY")
                return@evaluateJavascript
            }
            val req = Request.Builder().url(url).build()
            okhttp.value.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, e.stackTraceToString())
                }

                override fun onResponse(call: Call, response: Response) {
                    val img = response.body?.bytes() ?: return
                    val bitmap = BitmapFactory.decodeByteArray(img, 0, img.size)
                    processImg(bitmap)
                }

            })
        }

    }

    private fun processImg(bitmap: Bitmap) {

        runOnUiThread {
            webview.evaluateJavascript(
                """document.getElementById("slideBlock").style.top.slice(0,-2) 
/ document.getElementById("slideBg").height
* document.getElementById("slideBg").naturalHeight """
            ) {
                Log.d(TAG, "height $it")
                val height = it.toFloat().toInt()
                val newMap = Bitmap.createBitmap(bitmap, 0, height + 10, bitmap.width, 120)
                val top2 = calculate(newMap)
                slide(top2)
            }

        }

    }

    fun indexesOfTopElements(orig: IntArray, nummax: Int): IntArray {
        val copy = Arrays.copyOf(orig, orig.size)
        Arrays.sort(copy)
        val honey = Arrays.copyOfRange(copy, copy.size - nummax, copy.size)
        val result = IntArray(nummax)
        var resultPos = 0
        for (i in orig.indices) {
            val onTrial = orig[i]
            val index = Arrays.binarySearch(honey, onTrial)
            if (index < 0) continue
            result[resultPos++] = i
        }
        return result
    }

    private fun calculate(bitmap: Bitmap): IntArray {
        val new_map = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(new_map)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        val result = IntArray(bitmap.width) { 0 }
        for (i in 0 until bitmap.width) {
            for (j in 0 until bitmap.height) {
                val pix = new_map.getPixel(i, j) and 0x000000FF
                result[i] += pix
            }
        }
        val top2 = indexesOfTopElements(result, 2)
        val linePaint = Paint()
        linePaint.color = Color.GREEN
        canvas.drawLine(
            top2[0].toFloat(),
            0f,
            top2[0].toFloat(),
            new_map.height.toFloat(),
            linePaint
        )
        canvas.drawLine(
            top2[1].toFloat(),
            0f,
            top2[1].toFloat(),
            new_map.height.toFloat(),
            linePaint
        )
        runOnUiThread {
            img_view.setImageBitmap(new_map)
        }
        return top2
    }

    private fun getDensity(): Float {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        return dm.density
    }

    private fun slide(top2: IntArray) {

        webview.evaluateJavascript("""JSON.stringify(document.getElementById("tcaptcha_drag_thumb").getBoundingClientRect())""") { drag ->
            Log.d(TAG, drag)
            val json = JsonParser.parseString(drag.slice(1..drag.length - 2).replace("\\", ""))
            val dpi = getDensity()

            val width = json.asJsonObject.get("width").asFloat
            val height = json.asJsonObject.get("height").asFloat

            var x = json.asJsonObject.get("x").asFloat + (0.5f * width)
            var y = json.asJsonObject.get("y").asFloat + (0.5f * height)


            webview.evaluateJavascript(
                """document.getElementById("slideBg").width
/ document.getElementById("slideBg").naturalWidth"""
            ) {
                val values = it.toFloat()
                val img_value = (top2[1] - top2[0]) / 2 + top2[0]
                Log.d(TAG, "img_value $img_value")
                val target = (img_value * values) * dpi + 30

                Log.d(TAG, "target $target")
                x *= dpi
                y *= dpi
                Log.d(TAG, "slider $x $y")

                val rand = Random()
                var time = SystemClock.uptimeMillis()
                var down = time

                webview.dispatchTouchEvent(
                    MotionEvent.obtain(
                        down,
                        time,
                        MotionEvent.ACTION_DOWN,
                        x,
                        y,
                        0
                    )
                )
                time += 100

                for (i in x.toInt() until target.toInt()) {
                    webview.dispatchTouchEvent(
                        MotionEvent.obtain(
                            down,
                            time,
                            MotionEvent.ACTION_MOVE,
                            i.toFloat(),
                            y,
                            0
                        )
                    )
                    time += 1
                }

                time += 100
                webview.dispatchTouchEvent(
                    MotionEvent.obtain(
                        down,
                        down,
                        MotionEvent.ACTION_UP,
                        target,
                        y,
                        0
                    )
                )

            }

        }
    }
}
