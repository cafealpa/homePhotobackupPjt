package com.chochocho.homephotoclient.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.chochocho.homephotoclient.data.local.LocalAsset

/** MediaStore에서 백업 대상 파일을 나열한다. 기본 범위: DCIM (카메라 사진·동영상). */
object MediaScanner {

    fun scanDcim(context: Context): List<LocalAsset> =
        scan(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) +
            scan(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    private fun scan(context: Context, collection: Uri): List<LocalAsset> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val cursor = context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/%"),
            null,
        ) ?: return emptyList()

        return cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(nameCol) ?: continue
                    val size = it.getLong(sizeCol)
                    if (size <= 0) continue
                    // 촬영시각(ms) 우선, 없으면 파일 수정시각(s → ms)
                    val mtime = when {
                        !it.isNull(takenCol) && it.getLong(takenCol) > 0 -> it.getLong(takenCol)
                        !it.isNull(modifiedCol) && it.getLong(modifiedCol) > 0 -> it.getLong(modifiedCol) * 1000
                        else -> null
                    }
                    add(
                        LocalAsset(
                            uri = ContentUris.withAppendedId(collection, it.getLong(idCol)).toString(),
                            displayName = name,
                            size = size,
                            mtime = mtime,
                            hash = null,
                            status = "NEW",
                            error = null,
                        )
                    )
                }
            }
        }
    }
}
