package uk.wearlab.activityrecognition.dialogs

import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.util.Log
import uk.wearlab.activityrecognition.R
import us.feras.mdv.MarkdownView
import java.io.BufferedReader
import java.io.InputStreamReader


class AboutDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // Use the Builder class for convenient dialog construction
            val builder = AlertDialog.Builder(it)
            // Get the layout inflater
            val inflater = requireActivity().layoutInflater

            // Inflate and set the layout for the dialog
            // Pass null as the parent view because its going in the dialog layout
            val view = inflater.inflate(uk.wearlab.activityrecognition.R.layout.about_dialog, null)

            Log.i("TAG", "ASSETS files")
            val markdownView = view.findViewById<MarkdownView>(R.id.markdownView)
            val markdownContent = readFile("test.md")
            markdownView.loadMarkdown(markdownContent)

            builder.setView(view)
                   .setPositiveButton("Got it") { dialog, id ->
                       dialog.cancel()
                   }

            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun readFile(path: String): String {
        val reader = BufferedReader(InputStreamReader(activity!!.assets.open(path)))
        return reader.use(BufferedReader::readText)
    }
}