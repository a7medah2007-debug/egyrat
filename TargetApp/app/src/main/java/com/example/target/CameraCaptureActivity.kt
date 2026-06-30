package com.example.target

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer

class CameraCaptureActivity : Activity() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val facing = intent.getStringExtra("facing") ?: "back"
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendErrorAndFinish("Camera permission not granted")
            return
        }

        takePhoto(facing)
    }

    private fun takePhoto(facing: String) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val desiredFacing = if (facing == "front") {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

            var selectedCameraId: String? = null
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == desiredFacing) {
                    selectedCameraId = cameraId
                    break
                }
            }

            if (selectedCameraId == null) {
                selectedCameraId = manager.cameraIdList.firstOrNull()
            }

            if (selectedCameraId == null) {
                sendErrorAndFinish("No camera available on this device")
                return
            }

            manager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                    sendErrorAndFinish("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                    sendErrorAndFinish("Camera error: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            sendErrorAndFinish("Failed to open camera: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        try {
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    sendImageAndFinish(bytes)
                } catch (e: Exception) {
                    sendErrorAndFinish("Error reading image: ${e.message}")
                }
            }, backgroundHandler)

            val dummyTexture = SurfaceTexture(10)
            dummyTexture.setDefaultBufferSize(640, 480)
            val previewSurface = Surface(dummyTexture)
            val readerSurface = imageReader!!.surface

            camera.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        triggerCapture(readerSurface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sendErrorAndFinish("Capture session configuration failed")
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            sendErrorAndFinish("Failed to create capture session: ${e.message}")
        }
    }

    private fun triggerCapture(readerSurface: Surface) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CameraFailure
                ) {
                    sendErrorAndFinish("Image capture failed: reason ${failure.reason}")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            sendErrorAndFinish("Failed to trigger capture: ${e.message}")
        }
    }

    private fun sendImageAndFinish(jpegBytes: ByteArray) {
        try {
            val tempFile = File(cacheDir, "captured_photo.jpg")
            tempFile.writeBytes(jpegBytes)
            val intent = Intent("com.example.target.PHOTO_CAPTURED").apply {
                putExtra("status", "success")
                putExtra("file_path", tempFile.absolutePath)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            sendErrorAndFinish("Failed to save image file: ${e.message}")
        }
        cleanupAndFinish()
    }

    private fun sendErrorAndFinish(errorMsg: String) {
        val intent = Intent("com.example.target.PHOTO_CAPTURED").apply {
            putExtra("status", "error")
            putExtra("message", errorMsg)
        }
        sendBroadcast(intent)
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            backgroundThread?.quitSafely()
        } catch (e: Exception) {
            // Ignore
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndFinish()
    }
}
