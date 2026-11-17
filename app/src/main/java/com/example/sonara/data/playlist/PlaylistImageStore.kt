package com.example.sonara.data.playlist

/**
 * Manages sandboxed local file persistence for user-created playlist cover artwork.
 * Constrains all disk operations strictly to the dedicated playlist covers directory.
 */
interface PlaylistImageStore {
    /**
     * Imports a user-selected image from a content URI, validates dimensions,
     * subsamples, corrects EXIF orientation, and saves it atomically to a private file.
     * Returns the generated file name (e.g. "cover_<uuid>.jpg").
     */
    suspend fun importImage(sourceUri: String): String

    /**
     * Resolves a stored cover filename or path to a validated, readable absolute file path.
     * Returns null if the file does not exist, cannot be read, or is empty.
     */
    suspend fun resolve(fileNameOrPath: String?): String?

    /**
     * Safely deletes a stored cover file if it belongs to the playlist covers directory.
     */
    suspend fun delete(fileNameOrPath: String?)
}
