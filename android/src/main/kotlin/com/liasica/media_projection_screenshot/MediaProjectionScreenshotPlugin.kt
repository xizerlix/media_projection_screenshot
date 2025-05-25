package com.liasica.media_projection_screenshot

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import im.zego.media_projection_creator.MediaProjectionCreatorCallback
import im.zego.media_projection_creator.RequestMediaProjectionPermissionManager
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** MediaProjectionScreenshotPlugin */
class MediaProjectionScreenshotPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {

    private lateinit var methodChannel: MethodChannel
    private lateinit var context: Context
    private var events: EventChannel.EventSink? = null

    private var mediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mImageReader: ImageReader? = null

    private var isLiving: AtomicBoolean = AtomicBoolean(false)
    private var processingTime = AtomicLong(System.currentTimeMillis())
    private var counting = AtomicLong(0)

    // Добавлен MediaProjection callback
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.i(LOG_TAG, "MediaProjection stopped by system")
            stopCapture()
        }
    }

    companion object {
        const val LOG_TAG = "MP_SCREENSHOT"
        const val CAPTURE_SINGLE = "MP_CAPTURE_SINGLE"
        const val CAPTURE_CONTINUOUS = "MP_CAPTURE_CONTINUOUS"
        const val METHOD_CHANNEL_NAME = "com.liasica.media_projection_screenshot/method"
        const val EVENT_CHANNEL_NAME = "com.liasica.media_projection_screenshot/event"
        const val FPS = 15
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
        methodChannel.setMethodCallHandler(this)

        EventChannel(flutterPluginBinding.binaryMessenger, EVENT_CHANNEL_NAME).setStreamHandler(this)

        context = flutterPluginBinding.applicationContext

        RequestMediaProjectionPermissionManager.getInstance().setRequestPermissionCallback(mediaProjectionCreatorCallback)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "takeCapture" -> takeCapture(call, result)
            "startCapture" -> startCapture(call, result)
            "stopCapture" -> stopCapture(result)
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
    }

    override fun onListen(arguments: Any?, es: EventChannel.EventSink?) {
        events = es
    }

    override fun onCancel(arguments: Any?) {}

    private val mediaProjectionCreatorCallback = MediaProjectionCreatorCallback { projection, errorCode ->
        when (errorCode) {
            RequestMediaProjectionPermissionManager.ERROR_CODE_SUCCEED -> {
                Log.i(LOG_TAG, "MediaProjection created successfully")
                mediaProjection = projection?.apply {
                    registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
                }
            }
            RequestMediaProjectionPermissionManager.ERROR_CODE_FAILED_USER_CANCELED -> {
                Log.e(LOG_TAG, "MediaProjection permission denied by user")
            }
            RequestMediaProjectionPermissionManager.ERROR_CODE_FAILED_SYSTEM_VERSION_TOO_LOW -> {
                Log.e(LOG_TAG, "Android version too low for MediaProjection")
            }
        }
    }

    private fun stopCapture(result: Result? = null) {
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        if (!isLiving.compareAndSet(true, false)) {
            Log.i(LOG_TAG, "Capture already stopped")
            result?.success(true)
            return
        }

        mVirtualDisplay?.release()
        mVirtualDisplay = null

        mImageReader?.surface?.release()
        mImageReader?.close()
        mImageReader = null

        Log.i(LOG_TAG, "Capture stopped successfully")
        result?.success(true)
    }

    @SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startCapture(call: MethodCall, result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            result.error(LOG_TAG, "API level too low", null)
            return
        }

        mediaProjection?.let { mp ->
            if (!isLiving.compareAndSet(false, true)) {
                result.error(LOG_TAG, "Capture already running", null)
                return
            }

            try {
                val metrics = Resources.getSystem().displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels

                mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 5).also {
                    mVirtualDisplay = mp.createVirtualDisplay(
                        CAPTURE_CONTINUOUS,
                        width,
                        height,
                        1,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        it.surface,
                        null,
                        null
                    )
                }

                setupImageReader(call)
                result.success(true)
            } catch (e: Exception) {
                stopCapture()
                result.error(LOG_TAG, "Start capture failed: ${e.message}", null)
            }
        } ?: run {
            result.error(LOG_TAG, "MediaProjection not initialized", null)
        }
    }

    @SuppressLint("WrongConstant")
    private fun takeCapture(call: MethodCall, result: Result) {
        mediaProjection?.let { mp ->
            try {
                val metrics = Resources.getSystem().displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels

                val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 5).apply {
                    mp.createVirtualDisplay(
                        CAPTURE_SINGLE,
                        width,
                        height,
                        1,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        surface,
                        null,
                        null
                    )
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    processSingleCapture(imageReader, call, result)
                }, 100)
            } catch (e: Exception) {
                result.error(LOG_TAG, "Capture failed: ${e.message}", null)
            }
        } ?: run {
            result.error(LOG_TAG, "MediaProjection not initialized", null)
        }
    }

    private fun setupImageReader(call: MethodCall) {
        val metrics = Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val region = call.arguments as? Map<*, *>
        val fps = region?.get("fps") as? Int ?: FPS

        mImageReader?.setOnImageAvailableListener({ reader ->
            processContinuousCapture(reader, width, height, region, fps)
        }, Handler(Looper.getMainLooper()))
    }

    private fun processContinuousCapture(
        reader: ImageReader,
        width: Int,
        height: Int,
        region: Map<*, *>?,
        fps: Int
    ) {
        val image = reader.acquireLatestImage() ?: return
        val startTime = System.currentTimeMillis()

        try {
            if (fps == 0 || startTime - processingTime.get() >= 1000 / fps) {
                processingTime.set(startTime)
                processImage(image, width, height, region)
            }
        } finally {
            image.close()
        }
    }

    private fun processSingleCapture(
        imageReader: ImageReader,
        call: MethodCall,
        result: Result
    ) {
        val image = imageReader.acquireLatestImage() ?: run {
            result.error(LOG_TAG, "Failed to acquire image", null)
            return
        }

        try {
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val processedData = processImage(image, width, height, call.arguments as? Map<*, *>)
            result.success(processedData)
        } catch (e: Exception) {
            result.error(LOG_TAG, "Image processing failed: ${e.message}", null)
        } finally {
            image.close()
            imageReader.close()
        }
    }

    private fun processImage(
        image: android.media.Image,
        width: Int,
        height: Int,
        region: Map<*, *>?
    ): Map<String, Any> {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val padding = rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(width + padding, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }

        val croppedBitmap = region?.let {
            val x = (it["x"] as? Int ?: 0) + padding / 2
            val y = it["y"] as? Int ?: 0
            val w = it["width"] as? Int ?: width
            val h = it["height"] as? Int ?: height
            bitmap.crop(x, y, w, h)
        } ?: bitmap

        return ByteArrayOutputStream().use { outputStream ->
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            mapOf(
                "bytes" to outputStream.toByteArray(),
                "width" to croppedBitmap.width,
                "height" to croppedBitmap.height,
                "rowBytes" to croppedBitmap.rowBytes,
                "format" to Bitmap.Config.ARGB_8888.toString(),
                "pixelStride" to pixelStride,
                "rowStride" to rowStride,
                "nv21" to getYV12(croppedBitmap.width, croppedBitmap.height, croppedBitmap),
                "time" to System.currentTimeMillis(),
                "queue" to counting.incrementAndGet()
            )
        }
    }

    private fun Bitmap.crop(x: Int, y: Int, width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(this, x, y, width, height, null, true)
    }

    private fun getYV12(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
        encodeYV12(yuv, argb, inputWidth, inputHeight)
        scaled.recycle()
        return yuv
    }

    private fun encodeYV12(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                yuv420sp[yIndex++] = y.toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420sp[uIndex++] = v.toByte()
                    yuv420sp[vIndex++] = u.toByte()
                }
            }
        }
    }
}