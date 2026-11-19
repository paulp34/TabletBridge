package com.vetspantry.tabletbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ImageUtils {

    /** Converts a CameraX YUV_420_888 frame to NV21 layout. */
    fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        return try {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val nv21 = ByteArray(ySize + 2 * (ySize / 4))

            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            if (yRowStride == width) {
                yBuffer.get(nv21, 0, ySize)
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(nv21, row * width, width)
                }
            }

            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            var pos = ySize
            for (row in 0 until height / 2) {
                var vIndex = row * vPlane.rowStride
                var uIndex = row * uPlane.rowStride
                for (col in 0 until width / 2) {
                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                    vIndex += vPlane.pixelStride
                    uIndex += uPlane.pixelStride
                }
            }
            nv21
        } catch (e: Exception) {
            null
        }
    }

    /** YUV frame -> JPEG (already rotated upright). Returns null on failure. */
    fun toJpeg(image: ImageProxy, quality: Int): ByteArray? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        var bytes = out.toByteArray()

        val degrees = image.imageInfo.rotationDegrees
        if (degrees != 0) {
            try {
                val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (src != null) {
                    val m = Matrix()
                    m.postRotate(degrees.toFloat())
                    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    val out2 = ByteArrayOutputStream()
                    rotated.compress(Bitmap.CompressFormat.JPEG, quality, out2)
                    bytes = out2.toByteArray()
                    if (rotated != src) src.recycle()
                    rotated.recycle()
                }
            } catch (e: Exception) {
                // fall back to unrotated JPEG
            }
        }
        return bytes
    }

    /** RGBA frame from an ImageReader (screen capture) -> JPEG. */
    fun rgbaToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val rowBytes = width * 4
            val packed = ByteArray(rowBytes * height)
            val buffer = plane.buffer
            buffer.rewind()
            if (rowStride == rowBytes) {
                buffer.get(packed)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(packed, row * rowBytes, rowBytes)
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
