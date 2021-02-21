package com.ashehata.cameraxsample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager.ACTION_MANAGE_STORAGE
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.FLASH_MODE_OFF
import androidx.camera.core.ImageCapture.FLASH_MODE_ON
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import de.hdodenhof.circleimageview.CircleImageView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private var savedUri: Uri? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null

    // TODO here you can change camera type (front OR back)
    // DEFAULT_FRONT_CAMERA or DEFAULT_BACK_CAMERA
    //  Select front camera as a default
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private var flashState = 0
    private lateinit var viewFinder: PreviewView

    /**
     *
     */
    private lateinit var camera_capture_button: Button
    private lateinit var camera_flash: Button
    private lateinit var camera_type: Button
    private lateinit var image: CircleImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        camera_capture_button = findViewById(R.id.camera_capture_button)
        camera_flash = findViewById(R.id.camera_flash)
        camera_type = findViewById(R.id.camera_type)
        viewFinder = findViewById(R.id.viewFinder)
        image = findViewById(R.id.image)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // getOutputDirectory()
        outputDirectory = getOutDir()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Set up the listener for take photo button
        setupTakePhoto()
        setupFlash()
        setupType()
        setupImage()

    }

    private fun setupImage() {
        image.setOnClickListener {
            openInGallery()
        }
    }

    private fun openInGallery() {
        val intent = Intent(Intent.ACTION_VIEW, savedUri).apply {
            type = "image/*"
        }
        startActivity(intent)
    }

    private fun setupTakePhoto() {
        camera_capture_button.setOnClickListener { takePhoto() }
    }

    private fun setupType() {
        camera_type.setOnClickListener {
            if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            }
            updateCamera()
        }
    }

    private fun updateCamera() {
        startCamera()
    }

    private fun setupFlash() {
        camera_flash.setOnClickListener {
            if (flashState == 0) {
                flashState = 1
                controlFlash(true)
            } else {
                flashState = 0
                controlFlash(false)
            }
            imageCapture?.flashMode = if (flashState == 0) FLASH_MODE_OFF else FLASH_MODE_ON
        }
    }

    private fun controlFlash(isOn: Boolean) {
        cameraControl?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                it.cameraControl.enableTorch(isOn)
            }
        }
    }


    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Snackbar.make(viewFinder, "Photo capture failed", Snackbar.LENGTH_SHORT)
                        .show()
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    savedUri = Uri.fromFile(photoFile)
                    Snackbar.make(
                        viewFinder,
                        "Photo capture succeeded",
                        Snackbar.LENGTH_SHORT
                    ).show()

                    // display the pic
                    Glide.with(this@MainActivity)
                        .load(savedUri)
                        .centerCrop()
                        .into(image)

                    val msg = "Photo capture succeeded: $savedUri"
                    //Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            initPreview()

            initImageCapture()

            initImageAnalyzer()

            bindAgain()

        }, ContextCompat.getMainExecutor(this))
    }

    private fun initPreview() {
        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(viewFinder.createSurfaceProvider())
            }
    }

    private fun initImageAnalyzer() {
        imageAnalyzer = ImageAnalysis.Builder()
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    Log.d(TAG, "Average luminosity: $luma")
                })
            }

    }

    private fun bindAgain() {
        try {
            // Unbind use cases before rebinding
            cameraProvider?.unbindAll()

            // Bind use cases to camera
            cameraControl = cameraProvider?.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalyzer
            )

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun initImageCapture() {
        imageCapture = ImageCapture.Builder()
            .setFlashMode(
                if (flashState == 0) FLASH_MODE_OFF else FLASH_MODE_ON
            )
            // TODO control the taken image options
            .setTargetResolution(Size(650, 950))
            .build()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun getOutDir(): File {
        var output: File = Environment.getDataDirectory()
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            // val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            // We can use following directories: MUSIC, PODCASTS, ALARMS, RINGTONES, NOTIFICATIONS, PICTURES, MOVIES
            val publicDir = Environment.getExternalStorageDirectory()
            output = File(publicDir, "My CameraX Sample")
            if (!output.exists()) output.mkdir()

        } else {
            // show a dialog first to request from user to free space up before save our pic
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                Intent(ACTION_MANAGE_STORAGE).apply {
                    startActivity(this)
                }
            }
        }
        return output
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }


}