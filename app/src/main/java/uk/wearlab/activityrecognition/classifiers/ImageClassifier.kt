package uk.wearlab.activityrecognition.classifiers

import android.app.Activity
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteOrder
import java.io.BufferedReader
import java.io.InputStreamReader
import android.text.style.ForegroundColorSpan
import android.text.SpannableString
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.graphics.Bitmap
import org.tensorflow.lite.Delegate
import uk.wearlab.activityrecognition.helpers.GpuDelegateHelper
import android.text.style.RelativeSizeSpan
import java.util.*
import java.util.AbstractMap
import kotlin.collections.*

abstract class ImageClassifier(activity: Activity) {

    private val TAG = ImageClassifier::class.qualifiedName

    /** Preallocated buffers for storing image data in.  */
    private val intValues = IntArray(getImageSizeX() * getImageSizeY())

    /** Options for configuring the Interpreter.  */
    private val tfliteOptions = Interpreter.Options()

    /** The loaded TensorFlow Lite model.  */
    private var tfliteModel: MappedByteBuffer? = null

    /** An instance of the driver class to run model inference with Tensorflow Lite.  */
    protected var tflite: Interpreter? = null

    /** Labels corresponding to the output of the vision model.  */
    private var labelList: List<String>? = null

    // Display preferences
    private val GOOD_PROB_THRESHOLD = 0.3f
    private val SMALL_COLOR = android.graphics.Color.DKGRAY

    /** Number of results to show in the UI.  */
    private val RESULTS_TO_SHOW = 8

    /** Dimensions of inputs.  */
    private val DIM_BATCH_SIZE = 1
    private val DIM_PIXEL_SIZE = 3

    /** A ByteBuffer to hold image data, to be feed into Tensorflow Lite as inputs.  */
    protected var imgData: ByteBuffer? = null

    /** holds a gpu delegate  */
    var gpuDelegate: Delegate? = null

    private val sortedLabels = PriorityQueue(
        RESULTS_TO_SHOW,
        Comparator<Map.Entry<String, Float>> { o1, o2 -> o1.value.compareTo(o2.value) }
    )

    init {
        tfliteModel = loadModelFile(activity)
        tflite = Interpreter(tfliteModel!!, tfliteOptions)
        labelList = loadLabelList(activity)
        imgData = ByteBuffer.allocateDirect(
            DIM_BATCH_SIZE
            * getImageSizeX()
            * getImageSizeY()
            * DIM_PIXEL_SIZE
            * getNumBytesPerChannel()
        )
        imgData!!.order(ByteOrder.nativeOrder())
        //filterLabelProbArray = Array(FILTER_STAGES) {FloatArray(getNumLabels())}
        Log.d(TAG, "Created a Tensorflow Lite Image Classifier.")
    }


    /** Memory-map the model file in Assets.  */
    private fun loadModelFile(activity: Activity): MappedByteBuffer {
        val fileDescriptor = activity.assets.openFd(getModelPath())
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /** Reads label list from Assets.  */
    private fun loadLabelList(activity: Activity): List<String> {
        var labelList = ArrayList<String>()
        val reader = BufferedReader(InputStreamReader(activity.assets.open(getLabelPath())))
        var allText = reader.use(BufferedReader::readText)
        val lines = allText.split(System.getProperty("line.separator"))
        lines.forEach { l -> labelList.add(l) }
        reader.close()
        return labelList
    }

    /** Classifies a frame from the preview stream.  */
    fun classifyFrame(bitmap: Bitmap, builder: SpannableStringBuilder): Long {
        if (tflite == null) {
            Log.e(TAG, "Image classifier has not been initialized; Skipped.")
            builder.append(SpannableString("Uninitialized Classifier."))
        }
        convertBitmapToByteBuffer(bitmap)
        // Here's where the magic happens!!!
        val startTime = SystemClock.uptimeMillis()
        runInference()
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to run model inference: " + java.lang.Long.toString(endTime - startTime))

        // Smooth the results across frames.
        //applyFilter()

        // Print the results.
        printTopKLabels(builder)
        val duration = endTime - startTime
        val span = SpannableString(duration.toString() + " ms")
        span.setSpan(ForegroundColorSpan(android.graphics.Color.DKGRAY), 0, span.length, 0)
        builder.append(span)

        return duration
    }

    // Writes Image data into a `ByteBuffer`.
    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        if (imgData == null) {
            return
        }
        imgData!!.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Convert the image to floating point.
        var pixel = 0
        val startTime = SystemClock.uptimeMillis()
        for (i in 0 until getImageSizeX()) {
            for (j in 0 until getImageSizeY()) {
                val `val` = intValues[pixel++]
                addPixelValue(`val`)
            }
        }
        val endTime = SystemClock.uptimeMillis()
        Log.d(TAG, "Timecost to put values into ByteBuffer: " + java.lang.Long.toString(endTime - startTime))
    }

    /** Prints top-K labels, to be shown in UI as the results.  */
    private fun printTopKLabels(builder: SpannableStringBuilder) {
        for (i in 0 until getNumLabels()) {
            sortedLabels.add(
                AbstractMap.SimpleEntry(labelList!![i], getNormalizedProbability(i))
            )
            if (sortedLabels.size > RESULTS_TO_SHOW) {
                sortedLabels.poll()
            }
        }

        val size = sortedLabels.size
        for (i in 0 until size) {
            val label = sortedLabels.poll()
            val span = SpannableString(String.format("%s: %4.2f\n", label.key, label.value))
            val color: Int
            // Make it white when probability larger than threshold.
            if (label.value > GOOD_PROB_THRESHOLD) {
                color = android.graphics.Color.BLACK
            } else {
                color = SMALL_COLOR
            }
            // Make first item bigger.
            if (i == size - 1) {
                val sizeScale = if (i == size - 1) 1.25f else 0.8f
                span.setSpan(RelativeSizeSpan(sizeScale), 0, span.length, 0)
            }
            span.setSpan(ForegroundColorSpan(color), 0, span.length, 0)
            builder.insert(0, span)
        }
    }

    private fun recreateInterpreter() {
        if (tflite != null) {
            tflite!!.close()
            // TODO(b/120679982)
            // gpuDelegate.close();
            tflite = Interpreter(tfliteModel!!, tfliteOptions)
        }
    }

    fun useGpu() {
        if (gpuDelegate == null && GpuDelegateHelper.isGpuDelegateAvailable()) {
            gpuDelegate = GpuDelegateHelper.createGpuDelegate()
            tfliteOptions.addDelegate(gpuDelegate)
            recreateInterpreter()
        }
    }

    fun useCPU() {
        tfliteOptions.setUseNNAPI(false)
        recreateInterpreter()
    }

    fun useNNAPI() {
        tfliteOptions.setUseNNAPI(true)
        recreateInterpreter()
    }

    fun setNumThreads(numThreads: Int) {
        tfliteOptions.setNumThreads(numThreads)
        recreateInterpreter()
    }

    /** Closes tflite to release resources.  */
    fun close() {
        tflite?.close()
        tflite = null
        tfliteModel = null
    }



    // Get the name of the model file stored in Assets.
    protected abstract fun getModelPath(): String

    // Get the name of the label file stored in Assets.
    protected abstract fun getLabelPath(): String

    // Get the image size along the x axis.
    public abstract fun getImageSizeX(): Int

    // Get the image size along the y axis.
    public abstract fun getImageSizeY(): Int

    // Get the number of bytes that is used to store a single color channel value.
    protected abstract fun getNumBytesPerChannel(): Int

    // Add pixelValue to byteBuffer.
    protected abstract fun addPixelValue(pixelValue: Int)

    // Read the probability value for the specified label This is either the original value as it was
    // read from the net's output or the updated value after the filter was applied.
    protected abstract fun getProbability(labelIndex: Int): Float

    // Set the probability value for the specified label.
    protected abstract fun setProbability(labelIndex: Int, value: Number)

    // Get the normalized probability value for the specified label. This is the final value as it
    // will be shown to the user.
    protected abstract fun getNormalizedProbability(labelIndex: Int): Float

    /**
     * Run inference using the prepared input in [.imgData]. Afterwards, the result will be
     * provided by getProbability().
     *
     *
     * This additional method is necessary, because we don't have a common base for different
     * primitive data types.
     */
    protected abstract fun runInference()

    // Get the total number of labels.
    protected fun getNumLabels(): Int {
        return labelList!!.size
    }
}