package com.rishatoffice.scan

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.CvType
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A standalone CameraX + OpenCV scanner. Corner detection runs on the captured full-resolution image. */
class ScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var capture: ImageCapture
    private lateinit var status: TextView
    private val pages = mutableListOf<Bitmap>()
    private var latest: Bitmap? = null
    private var showingReview = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission is required to scan documents", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) Toast.makeText(this, "OpenCV initialization failed; basic image mode will be used", Toast.LENGTH_LONG).show()
        buildCameraUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildCameraUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 17, 42)) }
        val header = TextView(this).apply { text = "Rishat Office Scan"; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(12, 18, 12, 18) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        status = TextView(this).apply { text = "কাগজটি ফ্রেমের মধ্যে রাখুন"; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(8, 12, 8, 12) }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 18) }
        val gallery = Button(this).apply { text = "শেষ / PDF"; setOnClickListener { finishScan() } }
        val shutter = Button(this).apply { text = "●  Scan"; textSize = 18f; setOnClickListener { takePhoto() } }
        controls.addView(gallery, LinearLayout.LayoutParams(0, 58, 1f)); controls.addView(shutter, LinearLayout.LayoutParams(0, 58, 1.4f))
        root.addView(header); root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(status); root.addView(controls)
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) { Toast.makeText(this, "Camera start failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (!::capture.isInitialized || showingReview) return
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                status.text = "কর্নার শনাক্ত ও এনহ্যান্স করা হচ্ছে…"
                Thread {
                    val result = try { loadOriented(file)?.let { detectCropAndEnhance(it) } } catch (_: Exception) { null }
                    runOnUiThread {
                        file.delete()
                        if (result == null) { status.text = "ছবি প্রসেস করা যায়নি, আবার চেষ্টা করুন"; Toast.makeText(this@ScannerActivity, "Scan processing failed", Toast.LENGTH_SHORT).show() }
                        else showReview(result)
                    }
                }.start()
            }
            override fun onError(exception: ImageCaptureException) { status.text = "ছবি তোলা যায়নি"; Toast.makeText(this@ScannerActivity, exception.localizedMessage ?: "Capture failed", Toast.LENGTH_LONG).show() }
        })
    }

    private fun loadOriented(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle(); return rotated
    }

    private fun detectCropAndEnhance(source: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return source
        val rgba = Mat(); Utils.bitmapToMat(source, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat(); Imgproc.Canny(gray, edges, 55.0, 160.0)
        val contours = ArrayList<MatOfPoint>(); Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        var best: MatOfPoint2f? = null; var bestArea = 0.0
        val imageArea = (source.width * source.height).toDouble()
        for (contour in contours) {
            val c2 = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(c2, true)
            val approx = MatOfPoint2f(); Imgproc.approxPolyDP(c2, approx, perimeter * 0.02, true)
            val area = kotlin.math.abs(Imgproc.contourArea(approx))
            if (approx.total() == 4L && area > imageArea * 0.12 && area > bestArea && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                best = approx; bestArea = area
            }
        }
        var warped = rgba
        val corners = best?.toArray()
        if (corners != null && corners.size == 4) {
            val ordered = orderCorners(corners)
            val width = max(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2])).toInt().coerceAtLeast(1)
            val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2])).toInt().coerceAtLeast(1)
            if (width > 40 && height > 40 && width.toLong() * height < 30_000_000L) {
                val src = MatOfPoint2f(*ordered)
                val dst = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1.0, 0.0), Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0))
                val transform = Imgproc.getPerspectiveTransform(src, dst)
                warped = Mat(); Imgproc.warpPerspective(rgba, warped, transform, Size(width.toDouble(), height.toDouble()))
                src.release(); dst.release(); transform.release()
            }
        }
        // Light document enhancement: white balance/contrast via grayscale local contrast while preserving color.
        val resultMat = Mat()
        if (warped.channels() == 4) {
            val rgb = Mat(); Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
            val lab = Mat(); Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            val channels = ArrayList<Mat>(); Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0)); clahe.apply(channels[0], channels[0])
            Core.merge(channels, lab); Imgproc.cvtColor(lab, resultMat, Imgproc.COLOR_Lab2RGB)
            val out = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
            Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_RGB2RGBA); Utils.matToBitmap(resultMat, out)
            if (warped !== rgba) warped.release()
            rgba.release(); gray.release(); edges.release(); resultMat.release(); rgb.release(); lab.release(); channels.forEach { it.release() }
            return out
        }
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, out)
        if (warped !== rgba) warped.release()
        rgba.release(); gray.release(); edges.release(); resultMat.release()
        return out
    }

    private fun orderCorners(points: Array<Point>): Array<Point> {
        val sums = points.map { it.x + it.y }; val diffs = points.map { it.x - it.y }
        return arrayOf(points[sums.indices.minBy { sums[it] }], points[diffs.indices.maxBy { diffs[it] }], points[sums.indices.maxBy { sums[it] }], points[diffs.indices.minBy { diffs[it] }])
    }
    private fun distance(a: Point, b: Point) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    private fun showReview(bitmap: Bitmap) {
        latest = bitmap; showingReview = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 17, 42)); setPadding(12, 12, 12, 12) }
        val title = TextView(this).apply { text = "স্ক্যান প্রিভিউ • ${pages.size + 1} পৃষ্ঠা"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(4, 8, 4, 12) }
        val image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
        row.addView(button("আবার তুলুন") { showingReview = false; latest = null; buildCameraUi(); startCamera() })
        row.addView(button("+ পৃষ্ঠা") { pages.add(bitmap); latest = null; showingReview = false; buildCameraUi(); startCamera(); status.text = "${pages.size} পৃষ্ঠা যোগ হয়েছে" })
        row.addView(button("PDF সেভ") { pages.add(bitmap); finishScan() })
        root.addView(title); root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(row)
        setContentView(root)
    }

    private fun finishScan() {
        if (pages.isEmpty()) { finish(); return }
        try {
            val docs = File(filesDir, "docs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val base = "Scan_$stamp"
            val pdf = PdfDocument()
            pages.forEachIndexed { index, bitmap ->
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1), index + 1).create()
                val page = pdf.startPage(pageInfo)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
            }
            FileOutputStream(File(docs, "$base.pdf")).use { pdf.writeTo(it) }; pdf.close()
            FileOutputStream(File(docs, "$base.jpg")).use { pages.first().compress(Bitmap.CompressFormat.JPEG, 92, it) }
            Toast.makeText(this, "${pages.size} পৃষ্ঠা PDF সেভ হয়েছে", Toast.LENGTH_LONG).show()
            pages.forEach { if (!it.isRecycled) it.recycle() }; pages.clear(); finish()
        } catch (e: Exception) { Toast.makeText(this, "Save failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
    }

    override fun onBackPressed() {
        if (pages.isNotEmpty()) finishScan() else super.onBackPressed()
    }
}
