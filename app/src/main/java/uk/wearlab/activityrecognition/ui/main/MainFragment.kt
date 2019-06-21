package uk.wearlab.activityrecognition.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.net.Uri
import android.os.*
import android.support.v4.app.Fragment
import android.support.v4.content.FileProvider
import android.util.Log
import android.view.*
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.lang.IllegalArgumentException
import java.util.*
import android.text.SpannableStringBuilder
import android.widget.*
import kotlinx.android.synthetic.main.main_fragment.*
import uk.wearlab.activityrecognition.helpers.GpuDelegateHelper
import uk.wearlab.activityrecognition.R
import uk.wearlab.activityrecognition.classifiers.*
import uk.wearlab.activityrecognition.helpers.AutoFitTextureView
import uk.wearlab.activityrecognition.dialogs.AboutDialog
import java.io.*


/**
 * Fragment for the camera
 */
class MainFragment : Fragment() {

    private val lock = Any()
    private var runClassifier = false
    private var classifier: ImageClassifier? = null

    // Battery
    private var batteryFile: File? = null
    private val batteryFilename = "battery_logs.txt"
    private var batteryStatus: Intent? = null
    private var batteryTsFrom: Long = -1L
    private var batteryPctFrom = -1.0f
    private var batteryPctTo = -1.0f

    // Write to file elements
    private val dirname = "wearlab"
    private var dirPath: File? = null
    private var file: File? = null
    private val filename = "recognition_logs.txt"

    // GUI elements
    private var textView: TextView? = null
    private lateinit var np: NumberPicker
    private lateinit var deviceView: ListView
    private lateinit var modelView: ListView

    /** Current indices of device and model.  */
    private var combinationIndex = 0
    private val nThreads = 10
    private var currentDevice = -1
    private var currentModel = -1
    private var currentNumThreads = -1

    /** An [AutoFitTextureView] for camera preview.  */
    private lateinit var textureView: AutoFitTextureView

    // Model parameter constants.
    private lateinit var gpu: String
    private lateinit var cpu: String
    private lateinit var nnApi: String
    private lateinit var vgg16Float: String
    private lateinit var vgg19Float: String
    private lateinit var mobilenetV1Float: String
    private lateinit var densenet169Float: String
    private lateinit var resnet50Float: String
    private var deviceStrings = ArrayList<String>()
    private var modelStrings = ArrayList<String>()

    // Camera preview settings
    private val MAX_PREVIEW_WIDTH = 1920
    private val MAX_PREVIEW_HEIGHT = 1080
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder

    // The camera device used is the rear camera
    private lateinit var cameraDevice: CameraDevice
    // Called when the camera changes its state
    private val deviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "camera device opened")
            if (camera != null) {
                cameraDevice = camera
                previewSession()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "camera device disconnected")
            camera?.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.d(TAG, "camera device error")
            // Finish the activity in case of an error
            this@MainFragment.activity?.finish()
        }

    }

    // An additional thread for running tasks that shouldn't block the UI
    private lateinit var backgroundThread: HandlerThread
    // A Handler for running tasks in the background.
    private lateinit var backgroundHandler: Handler

    private val cameraManager by lazy {
        activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // A CameraCaptureSession.CaptureCallback that handles events related to capture
    private val captureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {}

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long
        ) {}
    }

    private fun previewSession() {
        val surfaceTexture = textureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT)
        val surface = Surface(surfaceTexture)

        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(surface)

        cameraDevice.createCaptureSession(Arrays.asList(surface),
            object: CameraCaptureSession.StateCallback(){
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "creating capture session failed")
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    if (session != null) {
                        captureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureSession.setRepeatingRequest(
                            captureRequestBuilder.build(),
                            captureCallback,
                            null
                            )
                    }
                }

            }, null)
    }

    private fun closeCamera() {
        if (this::captureSession.isInitialized)
            captureSession.close()
        if (this::cameraDevice.isInitialized)
            cameraDevice.close()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        gpu = getString(R.string.gpu)
        cpu = getString(R.string.cpu)
        nnApi = getString(R.string.nnapi)
        vgg16Float = getString(R.string.vgg16Float)
        vgg19Float = getString(R.string.vgg19Float)
        mobilenetV1Float = getString(R.string.mobilenetV1Float)
        resnet50Float = getString(R.string.resnet50Float)
        densenet169Float = getString(R.string.densenet169Float)

        // Get references to widgets.
        textureView = view.findViewById(R.id.textureView) as AutoFitTextureView
        textView = view.findViewById(R.id.text) as TextView
        deviceView = view.findViewById(R.id.device) as ListView
        modelView = view.findViewById(R.id.model) as ListView

        // Build list of models
        modelStrings.add(vgg16Float)
        modelStrings.add(vgg19Float)
        modelStrings.add(mobilenetV1Float)
        modelStrings.add(resnet50Float)
        modelStrings.add(densenet169Float)

        // Battery level
        batteryStatus = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context?.registerReceiver(null, ifilter)
        }
        batteryPctFrom = batteryStatus!!.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level / scale.toFloat()
        }

        // Build list of devices
        val defaultModelIndex = 0
        deviceStrings.add(cpu)
        if (GpuDelegateHelper.isGpuDelegateAvailable()) {
            deviceStrings.add(gpu)
        }
        deviceStrings.add(nnApi)

        deviceView.adapter = ArrayAdapter<String>(
            context, R.layout.listview_row, R.id.listview_row_text, deviceStrings
        )
        deviceView.choiceMode = ListView.CHOICE_MODE_SINGLE
        deviceView.onItemClickListener = object : AdapterView.OnItemClickListener {
            override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                updateActiveModel()
            }
        }
        deviceView.setItemChecked(0, true)

        modelView.choiceMode = ListView.CHOICE_MODE_SINGLE
        val modelAdapter = ArrayAdapter(
            context, R.layout.listview_row, R.id.listview_row_text, modelStrings
        )
        modelView.adapter = modelAdapter
        modelView.setItemChecked(defaultModelIndex, true)
        modelView.onItemClickListener = object : AdapterView.OnItemClickListener {
            override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                updateActiveModel()
            }
        }

        np = view.findViewById(R.id.np) as NumberPicker
        np.minValue = 1
        np.maxValue = nThreads
        np.wrapSelectorWheel = true
        np.setOnValueChangedListener(
            NumberPicker.OnValueChangeListener { picker, oldVal, newVal -> updateActiveModel() })

        // Start initial model.
    }

    private fun updateActiveModel() {
        // Get UI information before delegating to background
        val modelIndex = modelView.getCheckedItemPosition()
        val deviceIndex = deviceView.getCheckedItemPosition()
        val numThreads = np.getValue()

        backgroundHandler.post {
            if (modelIndex == currentModel && deviceIndex == currentDevice
                && numThreads == currentNumThreads
            ) {
                // Should return
            } else {
                // Save battery level to file
                batteryPctTo = batteryStatus!!.let { intent ->
                    val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    level / scale.toFloat()
                }

                FileOutputStream(batteryFile, true).bufferedWriter().use { out ->
                    out.append(batteryTsFrom.toString() + " ")
                    out.append(System.currentTimeMillis().toString() + " ")
                    out.append(batteryPctFrom.toString() + " ")
                    out.append(batteryPctTo.toString() + " ")
                    out.append( (if (currentModel != -1) modelStrings[currentModel] else "none") + " ")
                    out.append( (if (currentDevice != -1) deviceStrings[currentDevice] else "none") + " ")
                    out.appendln(currentNumThreads.toString())
                }

                currentModel = modelIndex
                currentDevice = deviceIndex
                currentNumThreads = numThreads

                // Disable classifier while updating
                if (classifier != null) {
                    classifier?.close()
                    classifier = null
                }

                // Lookup names of parameters.
                val model = modelStrings.get(modelIndex)
                val device = deviceStrings.get(deviceIndex)

                Log.i(TAG, "Changing model to $model device $device")

                // Try to load model.
                try {
                    classifier = when(model) {
                        vgg16Float -> ImageClassifierVGG16(activity!!)
                        vgg19Float -> ImageClassifierVGG19(activity!!)
                        mobilenetV1Float -> ImageClassifierMobileNet(activity!!)
                        densenet169Float -> ImageClassifierDenseNet169(activity!!)
                        resnet50Float -> ImageClassifierResNet50(activity!!)
                        else -> null
                    }
                    if (classifier == null) {
                        showToast("Failed to load model")
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "Failed to load", e)
                    classifier = null
                }

                // Customize the interpreter to the type of device we want to use.
                classifier?.let {it ->

                    it.setNumThreads(numThreads)
                    if (device == cpu) {
                    } else if (device == gpu) {
                        if (!GpuDelegateHelper.isGpuDelegateAvailable()) {
                            showToast("gpu not in this build.")
                            classifier = null
                        } else {
                            it.useGpu()
                        }
                    } else if (device == nnApi) {
                        it.useNNAPI()
                    }
                }

                batteryTsFrom = System.currentTimeMillis()
                batteryPctFrom = batteryPctTo

            }
        }
    }

    // Classifies a frame from the preview stream.
    private fun classifyFrame() {
        if (classifier == null || activity == null || !this::cameraDevice.isInitialized) {
            // It's important to not call showToast every frame, or else the app will starve and
            // hang. updateActiveModel() already puts a error message up with showToast.
            // showToast("Uninitialized Classifier or invalid context.");
            return
        }

        classifier?.let { it ->
            val textToShow = SpannableStringBuilder()
            val bitmap = textureView.getBitmap(it.getImageSizeX(), it.getImageSizeY())
            val duration = it.classifyFrame(bitmap, textToShow)

            val timestamp = System.currentTimeMillis()
            Log.i(TAG, "timestamp: " + timestamp)
            Log.i(TAG, "CLASSIFICATION IS: " + textToShow.toString() + ", recognized in " + duration + " ms")
            Log.i(TAG, "current device, clf, numthreads = " + deviceStrings[currentDevice] + ", " + modelStrings[currentModel] + ", " + currentNumThreads)

            // Save these values to file
            FileOutputStream(file, true).bufferedWriter().use { out ->
                out.append(timestamp.toString() + " ")
                out.append(duration.toString() + " ")
                out.append(deviceStrings[currentDevice] + " ")
                out.append(modelStrings[currentModel] + " ")
                out.appendln(currentNumThreads.toString() + " ")
            }

            bitmap.recycle()
            showToast(textToShow)
            //backgroundHandler.postDelayed(changeModel, 2000)
        }

    }


    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2 Activity Recognition").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)

        // Start the classification
        synchronized(lock) {
            runClassifier = true
        }
        backgroundHandler.post(periodicClassify)

    }

    private val changeModel: Runnable = run {
        Runnable {

            // Change model, device, threads to cover all combinations
            if (currentNumThreads > -1 && currentDevice > -1 && currentModel > -1) {
                var cnt = 0
                var mposNew = -1
                var dposNew = -1
                var tposNew = -1
                for (i in 0 until nThreads)
                    for (j in 0 until modelStrings.size)
                        for (k in 0 until deviceStrings.size) {
                            if (cnt == combinationIndex) {
                                tposNew = i
                                mposNew = j
                                dposNew = k
                            }
                            cnt++
                        }

                activity?.runOnUiThread(Runnable {
                    np.value = tposNew + 1
                    modelView.performItemClick(
                        modelView.getChildAt(mposNew), mposNew,
                        modelView.adapter.getItemId(mposNew)
                    )
                    deviceView.performItemClick(
                        deviceView.getChildAt(dposNew), dposNew,
                        deviceView.adapter.getItemId(dposNew)
                    )
                })

                combinationIndex++
            }


            backgroundHandler.postDelayed(changeModel, 10000)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
            synchronized(lock) {
                runClassifier = false
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    private val periodicClassify: Runnable = run {
        Runnable {
            synchronized(lock) {
                if (runClassifier) {
                    classifyFrame()
                }
            }
            backgroundHandler.post(periodicClassify)
        }
    }

    private fun <T> cameraCharacteristics(cameraId: String, key: CameraCharacteristics.Key<T>) :T {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return when (key) {
            CameraCharacteristics.LENS_FACING -> characteristics.get(key)
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP -> characteristics.get(key)
            else -> throw IllegalArgumentException("Key not recognised")
        }
    }

    private fun cameraId(lens: Int) : String {
        var deviceId = listOf<String>()
        try {
            val cameraIdList = cameraManager.cameraIdList
            deviceId = cameraIdList.filter { lens == cameraCharacteristics(it, CameraCharacteristics.LENS_FACING) }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
        return deviceId[0]
    }

    private fun connectCamera() {
        val deviceId = cameraId(CameraCharacteristics.LENS_FACING_BACK)
        Log.d(TAG, "deviceId: $deviceId")
        try {
            cameraManager.openCamera(deviceId, deviceStateCallback, backgroundHandler)
        }
        catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            Log.e(TAG, "Open camera device interrupted while opened")
        } catch (e: SecurityException) {
            Log.e(TAG, e.toString())
        }
    }

    companion object {
        const val REQUEST_CAMERA_PERMISSION = 100
        const val REQUEST_WRITE_PERMISSION = 108
        private val TAG = MainFragment::class.qualifiedName
        @JvmStatic fun newInstance() = MainFragment()
    }

    // Handles several lifecycle events on the textureView
    private val surfaceTextureListener = object: TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = true

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            Log.d(TAG, "textureSurface width: $width height: $height")
            openCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // Helper function
    @AfterPermissionGranted(REQUEST_CAMERA_PERMISSION)
    private fun checkCameraPermission() {
        if (EasyPermissions.hasPermissions(activity!!, Manifest.permission.CAMERA)) {
            Log.d(TAG, "App has camera permission")
            connectCamera()
        } else {
            EasyPermissions.requestPermissions(activity!!,
                getString(R.string.camera_request_rationale),
                REQUEST_CAMERA_PERMISSION,
                Manifest.permission.CAMERA)
        }
    }

    @AfterPermissionGranted(REQUEST_WRITE_PERMISSION)
    private fun checkExternalStoragePermission() {
        if (EasyPermissions.hasPermissions(activity!!, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Log.d(TAG, "App has write permission")
            createFolder()
        } else {
            EasyPermissions.requestPermissions(activity!!,
                getString(R.string.external_request_rationale),
                REQUEST_WRITE_PERMISSION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun createFolder() {
        // Directory to save files
        dirPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), dirname)
        Log.i(TAG, dirPath?.mkdirs().toString()+ ", dirPath ----------------------------- " + dirPath)
        file = File(dirPath, filename)
        file?.createNewFile()
        batteryFile = File(dirPath, batteryFilename)
        batteryFile?.createNewFile()
    }

    override fun onResume() {
        super.onResume()

        openExternalStorage()

        startBackgroundThread()
        // Make the textureView available
        if (textureView.isAvailable)
            openCamera()
        else
            textureView.surfaceTextureListener = surfaceTextureListener

    }

    private fun openCamera() {
        checkCameraPermission()
    }

    private fun openExternalStorage() {
        checkExternalStoragePermission()
    }



    /**
      * Shows a {@link Toast} on the UI thread for the classification results.
      *
      * @param text The message to show
      */
    private fun showToast(s: CharSequence) {
        Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

        activity!!.runOnUiThread {
            textView?.setText(s, TextView.BufferType.SPANNABLE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()

        super.onPause()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        //super.onCreateOptionsMenu(menu, inflater)
        inflater?.inflate(R.menu.menu_main, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAbout() {
        val dialog = AboutDialog()
        dialog.show(fragmentManager, "About")
    }


}
