/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.picker

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract
import android.provider.CloudMediaProviderContract.AlbumColumns
import android.provider.CloudMediaProviderContract.MediaCollectionInfo
import android.provider.CloudMediaProviderContract.MediaColumns
import androidx.annotation.RequiresExtension
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.data.entity.CloudMediaEntity
import com.dot.gallery.cloud.data.repository.CloudRepository
import com.dot.gallery.cloud.offline.CloudMediaCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import androidx.core.net.toUri

@RequiresExtension(extension = Build.VERSION_CODES.R, version = 3)
class DotCloudMediaProvider : CloudMediaProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun cloudMediaDao(): CloudMediaDao
        fun cloudMediaCache(): CloudMediaCache
        fun cloudRepository(): CloudRepository
    }

    private val entryPoint: ProviderEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context!!.applicationContext,
            ProviderEntryPoint::class.java
        )
    }

    private val dao: CloudMediaDao get() = entryPoint.cloudMediaDao()
    private val cache: CloudMediaCache get() = entryPoint.cloudMediaCache()
    private val cloudRepository: CloudRepository get() = entryPoint.cloudRepository()

    private val collectionId: String
        get() = "${context?.packageName ?: "com.dot.gallery"}_cloud_collection"

    private fun createSyncExtras(honoredSyncGen: Boolean = false): Bundle {
        return Bundle().apply {
            putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, collectionId)
            putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, collectionId)
            putString("media_collection_id", collectionId)
            putString("android.provider.extra.MEDIA_COLLECTION_ID", collectionId)

            if (honoredSyncGen) {
                putStringArrayList(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayListOf(CloudMediaProviderContract.EXTRA_SYNC_GENERATION)
                )
            }
        }
    }

    private fun createSafeCursor(columns: Array<String>, honoredSyncGen: Boolean = false): MatrixCursor {
        val syncExtras = createSyncExtras(honoredSyncGen)
        return object : MatrixCursor(columns) {
            override fun getExtras(): Bundle = syncExtras
            override fun respond(extras: Bundle?): Bundle = syncExtras
        }
    }

    override fun onCreate(): Boolean = true

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle {
        return createSyncExtras().apply {
            putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, System.currentTimeMillis())
            putLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION, System.currentTimeMillis())
        }
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        val syncGen = extras.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION, -1L)
        val hasSyncGen = syncGen >= 0

        val cursor = createSafeCursor(MEDIA_COLUMNS, honoredSyncGen = hasSyncGen)
        val requestedAlbumId = extras.getString(CloudMediaProviderContract.EXTRA_ALBUM_ID)

        runBlocking {
            val allItems = dao.getAllCachedAsync()

            val filteredItems = if (requestedAlbumId != null) {
                allItems.filter {
                    it.serverConfigId.toString() == requestedAlbumId || it.remoteId == requestedAlbumId
                }
            } else if (hasSyncGen) {
                allItems.filter { it.lastSyncedAt >= syncGen }
            } else {
                allItems
            }

            for (item in filteredItems) {
                val mediaStoreUri: String? = item.fileId.toLongOrNull()?.let {
                    "content://media/external/images/media/$it"
                }

                cursor.addRow(
                    arrayOf<Any?>(
                        item.globalMediaId.toString(),
                        mediaStoreUri,
                        item.takenTimestamp ?: item.timestamp,
                        item.lastSyncedAt,
                        item.mimeType,
                        item.size,
                        item.duration?.toLongOrNull() ?: 0L
                    )
                )
            }
        }

        return cursor
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        val syncGen = extras.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION, -1L)
        return createSafeCursor(arrayOf(MediaColumns.ID), honoredSyncGen = (syncGen >= 0))
    }

    override fun onQueryAlbums(extras: Bundle): Cursor {
        val cursor = createSafeCursor(ALBUM_COLUMNS)

        runBlocking {
            val allItems = dao.getAllCachedAsync()
            val albumGroups = allItems.groupBy { it.serverConfigId.toString() }

            for ((albumId, itemsInAlbum) in albumGroups) {
                val newestItem = itemsInAlbum.maxByOrNull { it.takenTimestamp ?: it.timestamp }
                val coverId = newestItem?.globalMediaId?.toString()

                cursor.addRow(
                    arrayOf<Any?>(
                        albumId,
                        "Album #$albumId",
                        newestItem?.takenTimestamp ?: newestItem?.timestamp ?: System.currentTimeMillis(),
                        itemsInAlbum.size,
                        coverId
                    )
                )
            }
        }

        return cursor
    }

    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val globalId = mediaId.toLongOrNull()
            ?: throw FileNotFoundException("Invalid media ID: $mediaId")

        val entity = runBlocking { dao.getByGlobalMediaId(globalId) }
            ?: throw FileNotFoundException("No record found for globalMediaId: $mediaId")

        val previewKey = cache.keyFor(entity.providerType, entity.serverConfigId, entity.remoteId, "preview")
        val thumbKey = cache.keyFor(entity.providerType, entity.serverConfigId, entity.remoteId, "thumbnail")

        val cachedFile = cache.get(previewKey) ?: cache.get(thumbKey)

        val targetFile = if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            cachedFile
        } else {
            downloadAssetViaRepository(entity)
        }

        val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun onOpenMedia(
        mediaId: String,
        extras: Bundle?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val globalId = mediaId.toLongOrNull()
            ?: throw FileNotFoundException("Invalid media ID: $mediaId")

        val entity = runBlocking { dao.getByGlobalMediaId(globalId) }
            ?: throw FileNotFoundException("No cloud record found for ID: $mediaId")

        // 1. Resolve local copy if present on device
        if (entity.localCopyPath.isNotBlank()) {
            val localUri = entity.localCopyPath.toUri()
            if (localUri.scheme == "content") {
                context?.contentResolver?.openFileDescriptor(localUri, "r")?.let { return it }
            }
            val localFile = if (localUri.scheme == "file") File(localUri.path ?: "") else File(entity.localCopyPath)
            if (localFile.exists() && localFile.length() > 0) {
                return ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        // 2. Check full cached original file
        val fullCacheKey = cache.keyFor(entity.providerType, entity.serverConfigId, entity.remoteId, "original")
        val cachedOriginal = cache.get(fullCacheKey)
        if (cachedOriginal != null && cachedOriginal.exists() && cachedOriginal.length() > 0) {
            return ParcelFileDescriptor.open(cachedOriginal, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // 3. Download original asset via CloudRepository
        val tempOriginalFile = downloadAssetViaRepository(entity)
        return ParcelFileDescriptor.open(tempOriginalFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun getFileExtension(mimeType: String): String {
        return when {
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> ".jpg"
            mimeType.contains("png", ignoreCase = true) -> ".png"
            mimeType.contains("webp", ignoreCase = true) -> ".webp"
            mimeType.contains("mp4", ignoreCase = true) -> ".mp4"
            mimeType.contains("jxl", ignoreCase = true) -> ".jxl"
            else -> ".bin"
        }
    }

    private fun downloadAssetViaRepository(entity: CloudMediaEntity): File {
        val ext = getFileExtension(entity.mimeType)
        val fileName = entity.label.ifBlank { "picker_orig_${entity.globalMediaId}$ext" }
        val targetTemp = File(context!!.cacheDir, fileName)

        val downloadResult = runBlocking {
            cloudRepository.downloadAsset(entity.providerType, entity.remoteId)
        }
        val downloadedUri = downloadResult.getOrNull()
            ?: throw FileNotFoundException(
                "Failed to download asset via CloudRepository for ${entity.remoteId}: ${downloadResult.exceptionOrNull()?.message}"
            )

        if (downloadedUri.scheme == "content") {
            context!!.contentResolver.openInputStream(downloadedUri)?.use { input ->
                FileOutputStream(targetTemp).use { output ->
                    input.copyTo(output)
                }
            } ?: throw FileNotFoundException("Cannot open stream from URI: $downloadedUri")
            return targetTemp
        }

        val f = File(downloadedUri.path ?: "")
        if (f.exists() && f.length() > 0) return f

        throw FileNotFoundException("Downloaded file does not exist at path: ${downloadedUri.path}")
    }

    companion object {
        private val MEDIA_COLUMNS = arrayOf(
            MediaColumns.ID,
            MediaColumns.MEDIA_STORE_URI,
            MediaColumns.DATE_TAKEN_MILLIS,
            MediaColumns.SYNC_GENERATION,
            MediaColumns.MIME_TYPE,
            MediaColumns.SIZE_BYTES,
            MediaColumns.DURATION_MILLIS
        )

        private val ALBUM_COLUMNS = arrayOf(
            AlbumColumns.ID,
            AlbumColumns.DISPLAY_NAME,
            AlbumColumns.DATE_TAKEN_MILLIS,
            AlbumColumns.MEDIA_COUNT,
            AlbumColumns.MEDIA_COVER_ID
        )
    }
}