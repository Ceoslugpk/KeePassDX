/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.SetMainCredentialDialogFragment
import com.kunzisoft.keepass.activities.helpers.EntrySelectionHelper
import com.kunzisoft.keepass.activities.helpers.ExternalFileHelper
import com.kunzisoft.keepass.activities.helpers.SpecialMode
import com.kunzisoft.keepass.activities.helpers.setOpenDocumentClickListener
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.adapters.FileDatabaseHistoryAdapter
import com.kunzisoft.keepass.app.database.FileDatabaseHistoryAction
import com.kunzisoft.keepass.autofill.AutofillComponent
import com.kunzisoft.keepass.autofill.AutofillHelper
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.education.FileDatabaseSelectActivityEducation
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.DATABASE_URI_KEY
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.utils.DexUtil
import com.kunzisoft.keepass.utils.MagikeyboardUtil
import com.kunzisoft.keepass.utils.MenuUtil
import com.kunzisoft.keepass.utils.UriUtil.isContributingUser
import com.kunzisoft.keepass.utils.UriUtil.openUrl
import com.kunzisoft.keepass.utils.allowCreateDocumentByStorageAccessFramework
import com.kunzisoft.keepass.utils.getParcelableCompat
import com.kunzisoft.keepass.view.asError
import com.kunzisoft.keepass.view.showActionErrorIfNeeded
import com.kunzisoft.keepass.viewmodels.DatabaseFilesViewModel
import java.io.FileNotFoundException

import com.kunzisoft.keepass.activities.dialogs.DriveFilePickerFragment

class FileDatabaseSelectActivity : DatabaseModeActivity(),
        SetMainCredentialDialogFragment.AssignMainCredentialDialogListener,
        DriveFilePickerFragment.DriveFilePickerListener {

    // Views
    private lateinit var coordinatorLayout: CoordinatorLayout
    private var specialTitle: View? = null
    private var createDatabaseButtonView: View? = null
    private var openDatabaseButtonView: View? = null
    private var openFromCloudButtonView: View? = null

    private val databaseFilesViewModel: DatabaseFilesViewModel by viewModels()

    private val mFileDatabaseSelectActivityEducation = FileDatabaseSelectActivityEducation(this)

    // Adapter to manage database history list
    private var mAdapterDatabaseHistory: FileDatabaseHistoryAdapter? = null

    private var mFileDatabaseHistoryAction: FileDatabaseHistoryAction? = null

    private var mDatabaseFileUri: Uri? = null

    private var mExternalFileHelper: ExternalFileHelper? = null

    private var mAutofillActivityResultLauncher: ActivityResultLauncher<Intent>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            AutofillHelper.buildActivityResultLauncher(this)
        else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enabling/disabling MagikeyboardService is normally done by DexModeReceiver, but this
        // additional check will allow the keyboard to be reenabled more easily if the app crashes
        // or is force quit within DeX mode and then the user leaves DeX mode. Without this, the
        // user would need to enter and exit DeX mode once to reenable the service.
        MagikeyboardUtil.setEnabled(this, !DexUtil.isDexMode(resources.configuration))

        mFileDatabaseHistoryAction = FileDatabaseHistoryAction.getInstance(applicationContext)

        setContentView(R.layout.activity_file_selection)
        coordinatorLayout = findViewById(R.id.activity_file_selection_coordinator_layout)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = ""
        setSupportActionBar(toolbar)

        // Special title
        specialTitle = findViewById(R.id.file_selection_title_part_3)

        // Create database button
        createDatabaseButtonView = findViewById(R.id.create_database_button)
        createDatabaseButtonView?.setOnClickListener { createNewFile() }

        // Open database button
        mExternalFileHelper = ExternalFileHelper(this)
        mExternalFileHelper?.buildOpenDocument { uri ->
            uri?.let {
                launchPasswordActivityWithPath(uri)
            }
        }
        mExternalFileHelper?.buildCreateDocument("application/x-keepass") { databaseFileCreatedUri ->
            mDatabaseFileUri = databaseFileCreatedUri
            if (mDatabaseFileUri != null) {
                SetMainCredentialDialogFragment.getInstance(true)
                    .show(supportFragmentManager, "passwordDialog")
            } else {
                val error = getString(R.string.error_create_database)
                Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
                Log.e(TAG, error)
            }
        }
        openDatabaseButtonView = findViewById(R.id.open_database_button)
        openDatabaseButtonView?.setOpenDocumentClickListener(mExternalFileHelper)

        openFromCloudButtonView = findViewById(R.id.open_from_cloud_button)
        openFromCloudButtonView?.setOnClickListener {
            val cloudSyncPrefs = getSharedPreferences(com.kunzisoft.keepass.settings.CloudSyncConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val isLinked = cloudSyncPrefs.getBoolean(com.kunzisoft.keepass.settings.CloudSyncConstants.PREF_IS_GOOGLE_DRIVE_LINKED, false)
            if (isLinked) {
                val account = GoogleSignIn.getLastSignedInAccount(this)
                if (account == null) {
                    // This should not happen if isLinked is true, but handle it just in case
                    Snackbar.make(coordinatorLayout, "Not signed in. Please link your account again.", Snackbar.LENGTH_LONG)
                        .setAction("Settings") {
                            startActivity(Intent(this, com.kunzisoft.keepass.settings.SettingsActivity::class.java))
                        }
                        .show()
                    return@setOnClickListener
                }

                val credential = GoogleAccountCredential.usingOAuth2(
                    this,
                    setOf(DriveScopes.DRIVE_FILE)
                )
                credential.selectedAccount = account.account
                val drive = Drive.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory(),
                    credential
                ).setApplicationName(getString(R.string.app_name)).build()

                listDriveFiles(drive)

            } else {
                Snackbar.make(coordinatorLayout, "Please link your Google Drive account in settings", Snackbar.LENGTH_LONG)
                    .setAction("Settings") {
                        startActivity(Intent(this, com.kunzisoft.keepass.settings.SettingsActivity::class.java))
                    }
                    .show()
            }
        }

        // History list
        val fileDatabaseHistoryRecyclerView = findViewById<RecyclerView>(R.id.file_list)
        fileDatabaseHistoryRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        // Removes blinks
        (fileDatabaseHistoryRecyclerView.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        // Construct adapter with listeners
        mAdapterDatabaseHistory = FileDatabaseHistoryAdapter(this)
        mAdapterDatabaseHistory?.setOnDefaultDatabaseListener { databaseFile ->
            databaseFilesViewModel.setDefaultDatabase(databaseFile)
        }
        mAdapterDatabaseHistory?.setOnFileDatabaseHistoryOpenListener { fileDatabaseHistoryEntityToOpen ->
            fileDatabaseHistoryEntityToOpen.databaseUri?.let { databaseFileUri ->
                launchPasswordActivity(
                    databaseFileUri,
                    fileDatabaseHistoryEntityToOpen.keyFileUri,
                    fileDatabaseHistoryEntityToOpen.hardwareKey
                )
            }
        }
        mAdapterDatabaseHistory?.setOnFileDatabaseHistoryDeleteListener { fileDatabaseHistoryToDelete ->
            databaseFilesViewModel.deleteDatabaseFile(fileDatabaseHistoryToDelete)
            true
        }
        mAdapterDatabaseHistory?.setOnSaveAliasListener { fileDatabaseHistoryWithNewAlias ->
            // Update in app database
            databaseFilesViewModel.updateDatabaseFile(fileDatabaseHistoryWithNewAlias)
        }
        fileDatabaseHistoryRecyclerView.adapter = mAdapterDatabaseHistory

        // Load default database the first time
        databaseFilesViewModel.doForDefaultDatabase { databaseFileUri ->
            launchPasswordActivityWithPath(databaseFileUri)
        }

        // Retrieve the database URI provided by file manager after an orientation change
        if (savedInstanceState != null
                && savedInstanceState.containsKey(EXTRA_DATABASE_URI)) {
            mDatabaseFileUri = savedInstanceState.getParcelableCompat(EXTRA_DATABASE_URI)
        }

        // Observe list of databases
        databaseFilesViewModel.databaseFilesLoaded.observe(this) { databaseFiles ->
            try {
                when (databaseFiles.databaseFileAction) {
                    DatabaseFilesViewModel.DatabaseFileAction.NONE -> {
                        mAdapterDatabaseHistory?.replaceAllDatabaseFileHistoryList(databaseFiles.databaseFileList)
                    }
                    DatabaseFilesViewModel.DatabaseFileAction.ADD -> {
                        databaseFiles.databaseFileToActivate?.let { databaseFileToAdd ->
                            mAdapterDatabaseHistory?.addDatabaseFileHistory(databaseFileToAdd)
                        }
                    }
                    DatabaseFilesViewModel.DatabaseFileAction.UPDATE -> {
                        databaseFiles.databaseFileToActivate?.let { databaseFileToUpdate ->
                            mAdapterDatabaseHistory?.updateDatabaseFileHistory(databaseFileToUpdate)
                        }
                    }
                    DatabaseFilesViewModel.DatabaseFileAction.DELETE -> {
                        databaseFiles.databaseFileToActivate?.let { databaseFileToDelete ->
                            mAdapterDatabaseHistory?.deleteDatabaseFileHistory(databaseFileToDelete)
                        }
                    }
                }
                databaseFilesViewModel.consumeAction()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to observe database action", e)
            }
        }

        // Observe default database
        databaseFilesViewModel.defaultDatabase.observe(this) {
            // Retrieve settings for default database
            mAdapterDatabaseHistory?.setDefaultDatabase(it)
        }
    }

    private fun listDriveFiles(drive: Drive) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    drive.files().list()
                        .setQ("mimeType != 'application/vnd.google-apps.folder' and trashed = false and name contains '.kdbx'")
                        .setSpaces("drive")
                        .setFields("nextPageToken, files(id, name)")
                        .execute()
                }
                val files = result.files
                if (files.isNullOrEmpty()) {
                    Snackbar.make(coordinatorLayout, "No .kdbx files found in your Google Drive", Snackbar.LENGTH_LONG).show()
                } else {
                    val fileNames = files.map { it.name }.toTypedArray()
                    val fileIds = files.map { it.id }.toTypedArray()
                    DriveFilePickerFragment.newInstance(fileNames, fileIds)
                        .show(supportFragmentManager, "driveFilePicker")
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Error listing files", e)
                Snackbar.make(coordinatorLayout, "Error listing files from Google Drive", Snackbar.LENGTH_LONG).asError().show()
            }
        }
    }

    override fun onFileSelected(fileId: String, fileName: String) {
        lifecycleScope.launch {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@FileDatabaseSelectActivity)
                if (account == null) {
                    // This should not happen, but handle it
                    return@launch
                }
                val credential = GoogleAccountCredential.usingOAuth2(
                    this@FileDatabaseSelectActivity,
                    setOf(DriveScopes.DRIVE_FILE)
                )
                credential.selectedAccount = account.account
                val drive = Drive.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory(),
                    credential
                ).setApplicationName(getString(R.string.app_name)).build()

                val outputStream = java.io.FileOutputStream(java.io.File(cacheDir, fileName))
                withContext(Dispatchers.IO) {
                    drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
                }
                val file = java.io.File(cacheDir, fileName)
                val uri = Uri.fromFile(file)

                // Save the Drive ID and local path for the sync worker
                val cloudSyncPrefs = getSharedPreferences(com.kunzisoft.keepass.settings.CloudSyncConstants.PREFS_NAME, Context.MODE_PRIVATE)
                cloudSyncPrefs.edit()
                    .putString("drive_file_id_${uri.toString()}", fileId)
                    .putString("local_file_path_${uri.toString()}", file.absolutePath)
                    .apply()

                launchPasswordActivityWithPath(uri)

            } catch (e: Exception) {
                Log.e("CloudSync", "Error downloading file", e)
                Snackbar.make(coordinatorLayout, "Error downloading file", Snackbar.LENGTH_LONG).asError().show()
            }
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase?) {
        super.onDatabaseRetrieved(database)
        if (database != null) {
            launchGroupActivityIfLoaded(database)
        }
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)

        if (result.isSuccess) {
            // Update list
            when (actionTask) {
                ACTION_DATABASE_CREATE_TASK,
                ACTION_DATABASE_LOAD_TASK -> {
                    result.data?.getParcelableCompat<Uri>(DATABASE_URI_KEY)?.let { databaseUri ->
                        val mainCredential =
                            result.data?.getParcelableCompat(DatabaseTaskNotificationService.MAIN_CREDENTIAL_KEY)
                                ?: MainCredential()
                        databaseFilesViewModel.addDatabaseFile(
                            databaseUri,
                            mainCredential.keyFileUri,
                            mainCredential.hardwareKey
                        )
                    }
                }
            }
            // Launch activity
            when (actionTask) {
                ACTION_DATABASE_CREATE_TASK -> {
                    GroupActivity.launch(
                        this@FileDatabaseSelectActivity,
                        database,
                        PreferencesUtil.enableReadOnlyDatabase(this@FileDatabaseSelectActivity)
                    )
                }
                ACTION_DATABASE_LOAD_TASK -> {
                    launchGroupActivityIfLoaded(database)
                }
            }
        }
        coordinatorLayout.showActionErrorIfNeeded(result)
    }

    /**
     * Create a new file by calling the content provider
     */
    private fun createNewFile() {
        mExternalFileHelper?.createDocument(
            getString(R.string.database_file_name_default) +
                getString(R.string.database_file_extension_default))
    }

    private fun fileNoFoundAction(e: FileNotFoundException) {
        val error = getString(R.string.file_not_found_content)
        Log.e(TAG, error, e)
        Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
    }

    private fun launchPasswordActivity(databaseUri: Uri, keyFile: Uri?, hardwareKey: HardwareKey?) {
        MainCredentialActivity.launch(this,
                databaseUri,
                keyFile,
                hardwareKey,
                { exception ->
                    fileNoFoundAction(exception)
                },
                { onCancelSpecialMode() },
                { onLaunchActivitySpecialMode() },
                mAutofillActivityResultLauncher)
    }

    private fun launchGroupActivityIfLoaded(database: ContextualDatabase) {
        if (database.loaded) {
            GroupActivity.launch(this,
                database,
                { onValidateSpecialMode() },
                { onCancelSpecialMode() },
                { onLaunchActivitySpecialMode() },
                mAutofillActivityResultLauncher)
        }
    }

    private fun launchPasswordActivityWithPath(databaseUri: Uri) {
        launchPasswordActivity(databaseUri, null, null)
        // Delete flickering for kitkat <=
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()

        // Define special title
        specialTitle?.isVisible = this.isContributingUser()

        // Show open and create button or special mode
        when (mSpecialMode) {
            SpecialMode.DEFAULT -> {
                if (packageManager.allowCreateDocumentByStorageAccessFramework()) {
                    // There is an activity which can handle this intent.
                    createDatabaseButtonView?.visibility = View.VISIBLE
                } else{
                    // No Activity found that can handle this intent.
                    createDatabaseButtonView?.visibility = View.GONE
                }
            }
            else -> {
                // Disable create button if in selection mode or request for autofill
                createDatabaseButtonView?.visibility = View.GONE
            }
        }

        mDatabase?.let { database ->
            launchGroupActivityIfLoaded(database)
        }

        // Show recent files if allowed
        if (PreferencesUtil.showRecentFiles(this@FileDatabaseSelectActivity)) {
            databaseFilesViewModel.loadListOfDatabases()
        } else {
            mAdapterDatabaseHistory?.clearDatabaseFileHistoryList()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // to retrieve the URI of a created database after an orientation change
        outState.putParcelable(EXTRA_DATABASE_URI, mDatabaseFileUri)
    }

    override fun onAssignKeyDialogPositiveClick(mainCredential: MainCredential) {
        try {
            mDatabaseFileUri?.let { databaseUri ->
                // Create the new database
                createDatabase(databaseUri, mainCredential)
            }
        } catch (e: Exception) {
            val error = getString(R.string.error_create_database_file)
            Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
            Log.e(TAG, error, e)
        }
    }

    override fun onAssignKeyDialogNegativeClick(mainCredential: MainCredential) {}

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        if (mSpecialMode == SpecialMode.DEFAULT) {
            MenuUtil.defaultMenuInflater(this, menuInflater, menu)
        }

        Handler(Looper.getMainLooper()).post {
            performedNextEducation()
        }

        return true
    }

    private fun performedNextEducation() {
        // If no recent files
        val createDatabaseEducationPerformed =
                createDatabaseButtonView != null
                && createDatabaseButtonView!!.visibility == View.VISIBLE
                && mFileDatabaseSelectActivityEducation.checkAndPerformedCreateDatabaseEducation(
                        createDatabaseButtonView!!,
                {
                    createNewFile()
                },
                {
                    // But if the user cancel, it can also select a database
                    performedNextEducation()
                })
        if (!createDatabaseEducationPerformed) {
            // selectDatabaseEducationPerformed
            openDatabaseButtonView != null
            && mFileDatabaseSelectActivityEducation.checkAndPerformedSelectDatabaseEducation(
                openDatabaseButtonView!!,
            { tapTargetView ->
                tapTargetView?.let {
                    mExternalFileHelper?.openDocument()
                }
            },
            {

            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> this.openUrl(R.string.file_manager_explanation_url)
        }
        MenuUtil.onDefaultMenuOptionsItemSelected(this, item)
        return super.onOptionsItemSelected(item)
    }

    companion object {

        private const val TAG = "FileDbSelectActivity"
        private const val EXTRA_DATABASE_URI = "EXTRA_DATABASE_URI"

        /*
         * -------------------------
         * 		Standard Launch
         * -------------------------
         */

        fun launch(context: Context) {
            context.startActivity(Intent(context, FileDatabaseSelectActivity::class.java))
        }

        /*
         * -------------------------
         * 		Search Launch
         * -------------------------
         */

        fun launchForSearchResult(context: Context,
                                  searchInfo: SearchInfo) {
            EntrySelectionHelper.startActivityForSearchModeResult(context,
                    Intent(context, FileDatabaseSelectActivity::class.java),
                    searchInfo)
        }

        /*
         * -------------------------
         * 		Save Launch
         * -------------------------
         */

        fun launchForSaveResult(context: Context,
                                searchInfo: SearchInfo) {
            EntrySelectionHelper.startActivityForSaveModeResult(context,
                    Intent(context, FileDatabaseSelectActivity::class.java),
                    searchInfo)
        }

        /*
         * -------------------------
         * 		Keyboard Launch
         * -------------------------
         */

        fun launchForKeyboardSelectionResult(activity: Activity,
                                             searchInfo: SearchInfo? = null) {
            EntrySelectionHelper.startActivityForKeyboardSelectionModeResult(activity,
                    Intent(activity, FileDatabaseSelectActivity::class.java),
                    searchInfo)
        }

        /*
         * -------------------------
         * 		Autofill Launch
         * -------------------------
         */

        @RequiresApi(api = Build.VERSION_CODES.O)
        fun launchForAutofillResult(activity: AppCompatActivity,
                                    activityResultLauncher: ActivityResultLauncher<Intent>?,
                                    autofillComponent: AutofillComponent,
                                    searchInfo: SearchInfo? = null) {
            AutofillHelper.startActivityForAutofillResult(activity,
                    Intent(activity, FileDatabaseSelectActivity::class.java),
                    activityResultLauncher,
                    autofillComponent,
                    searchInfo)
        }

        /*
         * -------------------------
         * 		Registration Launch
         * -------------------------
         */
        fun launchForRegistration(context: Context,
                                  registerInfo: RegisterInfo? = null) {
            EntrySelectionHelper.startActivityForRegistrationModeResult(context,
                    Intent(context, FileDatabaseSelectActivity::class.java),
                    registerInfo)
        }
    }
}
