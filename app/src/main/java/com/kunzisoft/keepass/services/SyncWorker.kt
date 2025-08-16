package com.kunzisoft.keepass.services

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.kunzisoft.keepass.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val driveFileId = inputData.getString(KEY_DRIVE_FILE_ID) ?: return Result.failure()
        val localFilePath = inputData.getString(KEY_LOCAL_FILE_PATH) ?: return Result.failure()

        Log.d(TAG, "Starting sync for $driveFileId")

        try {
            val account = GoogleSignIn.getLastSignedInAccount(applicationContext)
            if (account == null) {
                Log.e(TAG, "Not signed in, cannot sync")
                return Result.failure()
            }

            val credential = GoogleAccountCredential.usingOAuth2(
                applicationContext,
                setOf(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account
            val drive = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory(),
                credential
            ).setApplicationName(applicationContext.getString(R.string.app_name)).build()

            val cloudSyncPrefs = applicationContext.getSharedPreferences(com.kunzisoft.keepass.settings.CloudSyncConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val dbUri = "drive_file_id_$driveFileId" // A unique key for this db
            val lastSyncTimestamp = cloudSyncPrefs.getLong(com.kunzisoft.keepass.settings.CloudSyncConstants.PREF_LAST_SYNC_TIMESTAMP + "_$dbUri", 0)

            withContext(Dispatchers.IO) {
                // Get remote file metadata
                val remoteFile = drive.files().get(driveFileId).setFields("id, name, modifiedTime").execute()
                val remoteModifiedTime = remoteFile.modifiedTime.value

                // Get local file metadata
                val localFile = java.io.File(localFilePath)
                val localModifiedTime = localFile.lastModified()

                val remoteIsNewer = remoteModifiedTime > lastSyncTimestamp
                val localIsNewer = localModifiedTime > lastSyncTimestamp

                if (remoteIsNewer && !localIsNewer) {
                    // Download
                    Log.d(TAG, "Downloading remote file")
                    val outputStream = java.io.FileOutputStream(localFile)
                    drive.files().get(driveFileId).executeMediaAndDownloadTo(outputStream)
                    cloudSyncPrefs.edit().putLong(com.kunzisoft.keepass.settings.CloudSyncConstants.PREF_LAST_SYNC_TIMESTAMP + "_$dbUri", System.currentTimeMillis()).apply()
                } else if (localIsNewer && !remoteIsNewer) {
                    // Upload
                    Log.d(TAG, "Uploading local file")
                    val mediaContent = com.google.api.client.http.FileContent("application/x-keepass", localFile)
                    drive.files().update(driveFileId, remoteFile, mediaContent).execute()
                    cloudSyncPrefs.edit().putLong(com.kunzisoft.keepass.settings.CloudSyncConstants.PREF_LAST_SYNC_TIMESTAMP + "_$dbUri", System.currentTimeMillis()).apply()
                } else if (remoteIsNewer && localIsNewer) {
                    // Conflict
                    Log.w(TAG, "Conflict detected for $driveFileId")
                    // TODO: Handle conflict
                } else {
                    Log.d(TAG, "No changes detected for $driveFileId")
                }
            }

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            return Result.failure()
        }
    }

    companion object {
        const val KEY_DRIVE_FILE_ID = "drive_file_id"
        const val KEY_LOCAL_FILE_PATH = "local_file_path"
        private const val TAG = "SyncWorker"
    }
}
