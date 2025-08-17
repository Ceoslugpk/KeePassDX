package com.kunzisoft.keepass.activities.dialogs

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.kunzisoft.keepass.R

class ShareEntryDialogFragment : DialogFragment() {

    interface ShareEntryListener {
        fun onPasswordSet(password: String)
    }

    private var listener: ShareEntryListener? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_share_entry, null)
        val passwordEditText = view.findViewById<EditText>(R.id.password)
        val confirmPasswordEditText = view.findViewById<EditText>(R.id.confirm_password)

        builder.setView(view)
            .setPositiveButton("Share") { _, _ ->
                val password = passwordEditText.text.toString()
                val confirmPassword = confirmPasswordEditText.text.toString()
                if (password.isNotEmpty() && password == confirmPassword) {
                    listener?.onPasswordSet(password)
                } else {
                    // TODO: Show an error message
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                dialog?.cancel()
            }
        return builder.create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            listener = activity as ShareEntryListener
        } catch (e: ClassCastException) {
            throw ClassCastException("$activity must implement ShareEntryListener")
        }
    }
}
