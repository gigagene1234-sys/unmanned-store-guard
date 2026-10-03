package com.storeguard.hub.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.storeguard.hub.MainActivity
import com.storeguard.hub.R
import com.storeguard.hub.data.CollectionLogEntity
import com.storeguard.hub.data.StoreGuardDatabase
import com.storeguard.hub.parser.AnsiPosParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-consented POS capture session.
 *
 * Safety properties:
 * - Starts only after Android's MediaProjection consent dialog is accepted.
 * - Shows a persistent foreground notification while capture is active.
 * - Auto-stops after SESSION_LIMIT_MS.
 * - Frames are kept in memory only, OCR'ed, then immediately recycled.
 * - Raw screenshots and raw OCR text are never written to disk.
 * - Only rows matching the ANSI POS transaction format are persisted.
 */
class PosCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerThread = HandlerThread("StoreGuardPosCapture")
    private lateinit var worker: Handler
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val ocrBusy = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        workerThread.start()
        worker = Handler(workerThread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

        startProjection(resultCode, data)
        worker.postDelayed({ stopCapture() }, SESSION_LIMIT_MS)
        running.set(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        cleanupProjection()
        recognizer.close()
        workerThread.quitSafely()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProjection(resultCode: Int, data: Intent) {
        cleanupProjection()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, worker)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS || !ocrBusy.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            processFrame(image)
        }, worker)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "StoreGuard POS capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            worker
        )

        scope.launch {
            StoreGuardDatabase.get(applicationContext).dao().insertLog(
                CollectionLogEntity(
                    source = "POS_CAPTURE",
                    level = "INFO",
                    message = "사용자 승인 화면 수집 세션 시작. 원본 화면은 저장하지 않습니다.",
                    createdAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    private fun processFrame(image: Image) {
        var bitmap: Bitmap? = null
        try {
            bitmap = imageToBitmap(image)
        } catch (_: Throwable) {
            image.close()
            ocrBusy.set(false)
            return
        }
        image.close()

        val frame = bitmap ?: run {
            ocrBusy.set(false)
            return
        }

        recognizer.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                val capturedAt = System.currentTimeMillis()
                scope.launch {
                    val dao = StoreGuardDatabase.get(applicationContext).dao()
                    val parsed = AnsiPosParser.parseFlatText(lines, capturedAt)
                    var inserted = 0
                    parsed.forEach { payment ->
                        if (dao.insertPayment(payment) > 0L) inserted++
                    }
                    if (inserted > 0) {
                        dao.insertLog(
                            CollectionLogEntity(
                                source = "POS_OCR",
                                level = "INFO",
                                message = "POS 거래 신규 ${inserted}건 저장",
                                createdAtMillis = capturedAt
                            )
                        )
                    }
                }
            }
            .addOnCompleteListener {
                frame.recycle()
                ocrBusy.set(false)
            }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun stopCapture() {
        scope.launch {
            StoreGuardDatabase.get(applicationContext).dao().insertLog(
                CollectionLogEntity(
                    source = "POS_CAPTURE",
                    level = "INFO",
                    message = "POS 화면 수집 세션 종료",
                    createdAtMillis = System.currentTimeMillis()
                )
            )
        }
        cleanupProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupProjection() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "POS 화면 수집",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "사용자가 승인한 POS 화면 수집 세션이 실행 중일 때 표시됩니다."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PosCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("StoreGuard POS 수집 중")
            .setContentText("화면은 저장하지 않고 거래 형식 텍스트만 추출합니다.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "중지", stopIntent)
            .build()
    }

    companion object {
        const val ANSI_POS_PACKAGE = "com.annecypos.foodasp"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val ACTION_STOP = "com.storeguard.hub.action.STOP_POS_CAPTURE"

        private const val CHANNEL_ID = "storeguard_pos_capture"
        private const val NOTIFICATION_ID = 4102
        private const val FRAME_INTERVAL_MS = 1_500L
        private const val SESSION_LIMIT_MS = 15 * 60 * 1000L

        val running = AtomicBoolean(false)
    }
}
