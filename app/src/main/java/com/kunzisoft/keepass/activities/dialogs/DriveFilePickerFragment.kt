package com.kunzisoft.keepass.activities.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class DriveFilePickerFragment : DialogFragment() {

    interface DriveFilePickerListener {
        fun onFileSelected(fileId: String, fileName: String)
    }

    private var listener: DriveFilePickerListener? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val fileNames = arguments?.getStringArray(ARG_FILE_NAMES) ?: emptyArray()
        val fileIds = arguments?.getStringArray(ARG_FILE_IDS) ?: emptyArray()

        return AlertDialog.Builder(requireContext())
            .setTitle("Select a file")
            .setItems(fileNames) { _, which ->
                listener?.onFileSelected(fileIds[which], fileNames[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        private const val ARG_FILE_NAMES = "file_names"
        private const val ARG_FILE_IDS = "file_ids"

        fun newInstance(fileNames: Array<String>, fileIds: Array<String>): DriveFilePickerFragment {
            val fragment = DriveFilePickerFragment()
            val args = Bundle()
            args.putStringArray(ARG_FILE_NAMES, fileNames)
            args.putStringArray(ARG_FILE_IDS, fileIds)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            listener = activity as DriveFilePickerListener
        } catch (e: ClassCastException) {
            throw ClassCastException("$activity must implement DriveFilePickerListener")
        }
    }
}
