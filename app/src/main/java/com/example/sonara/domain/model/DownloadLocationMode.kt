package com.example.sonara.domain.model

/**
 * Represents where downloaded audio files are stored.
 *
 * CURRENT STATUS:
 *   APP_PRIVATE is the only fully operational mode.
 *   USER_SELECTED requires Android Storage Access Framework (SAF)
 *   integration through an Activity Result, which belongs to the
 *   frontend phase of implementation.
 *
 * APP_PRIVATE:
 *   Files stored in context.filesDir/sonara_downloads/.
 *   Path verified on-device as:
 *     /data/user/0/com.example.sonara/files/sonara_downloads/
 *   No storage permissions required (below Android 10 scoped storage boundary).
 *   Cleared on app uninstall only.
 *   Cannot be browsed by the user via Files app.
 *
 * USER_SELECTED:
 *   SAF URI obtained via ACTION_OPEN_DOCUMENT_TREE.
 *   Persistent URI permission must be taken via
 *     contentResolver.takePersistableUriPermission(uri, READ | WRITE).
 *   The persisted URI must survive app restart.
 *   URI permissions can be revoked by the system (user clears data,
 *     SD card removed, directory deleted). The domain layer must handle
 *     this by detecting an unavailable URI and falling back gracefully.
 *   Frontend wiring: StorageAccessActivity / registerForActivityResult
 *     with ActivityResultContracts.OpenDocumentTree.
 *
 * DO NOT move existing APP_PRIVATE downloads when the user switches to
 * USER_SELECTED — the DownloadEngine must respect the location at the time
 * of each download. Existing entries remain valid at their original paths.
 */
enum class DownloadLocationMode(
    val displayLabel: String
) {
    /** App-private internal storage — always available, no permissions needed. */
    APP_PRIVATE("App Storage (Default)"),

    /**
     * User-selected directory via SAF.
     * Persisted as a content:// URI in DataStore.
     * NOT OPERATIONAL until the frontend SAF picker is wired.
     */
    USER_SELECTED("Custom Folder (Select)");

    companion object {
        /** Safe deserialisation — returns APP_PRIVATE on unknown stored value. */
        fun fromName(name: String): DownloadLocationMode =
            entries.firstOrNull { it.name == name } ?: APP_PRIVATE
    }
}
