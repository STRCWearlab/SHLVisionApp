package uk.wearlab.activityrecognition.classifiers

import android.app.Activity
import android.util.Log


class ImageClassifierDenseNet169(activity: Activity) : ImageClassifier(activity) {

    private val TAG = ImageClassifier::class.qualifiedName
    private val name = "DenseNet169"

    /**
     * An array to hold inference results, to be feed into Tensorflow Lite as outputs. This isn't part
     * of the super class, because we need a primitive array here.
     */
    private var labelProbArray: Array<FloatArray>? = null

    /**
     * Initializes an `ImageClassifierFloatMobileNet`.
     *
     * @param activity
     */
    init {
        labelProbArray = Array(1) { FloatArray(getNumLabels()) }
    }

    override fun getModelPath(): String  = "DenseNet169.tflite";
    override fun getLabelPath(): String = "labels.txt"

    override fun getImageSizeX(): Int = 224
    override fun getImageSizeY(): Int = 224

    override fun getNumBytesPerChannel(): Int = 4 //Float.SIZE / Byte.SIZE

    override fun addPixelValue(pixelValue: Int) {
        imgData!!.putFloat((pixelValue shr 16 and 0xFF) / 255f)
        imgData!!.putFloat((pixelValue shr 8 and 0xFF) / 255f)
        imgData!!.putFloat((pixelValue and 0xFF) / 255f)
    }

    override fun getProbability(labelIndex: Int): Float = labelProbArray!![0][labelIndex];


    override fun setProbability(labelIndex: Int, value: Number) {
        labelProbArray!![0][labelIndex] = value.toFloat();
    }

    override fun getNormalizedProbability(labelIndex: Int): Float {
        return labelProbArray!![0][labelIndex];
    }

    override fun runInference() {
        tflite!!.run(imgData, labelProbArray)
        Log.i(TAG, "labelProbArray: " + labelProbArray?.get(0)?.joinToString())
    }
}