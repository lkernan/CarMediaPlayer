package com.saicmotor.media.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import coil.size.Size
import coil.transform.Transformation

/**
 * Coil [Transformation] that applies a Gaussian blur via RenderScript's
 * [ScriptIntrinsicBlur].
 *
 * Intended for full-screen ambient backgrounds where the image is loaded at
 * a moderate intermediate resolution (e.g. 300 px) and then upscaled by the
 * ImageView's centerCrop scaleType.  The combination of a real Gaussian blur
 * at that resolution + the bilinear upscale produces a smooth, non-pixelated
 * colour wash with no visible source pixels.
 *
 * RenderScript is deprecated as of API 31 (use RenderEffect on newer devices)
 * but remains the right choice here given the SAIC head unit's Android version.
 */
@Suppress("DEPRECATION")
class BlurTransformation(
    private val context: Context,
    private val radius: Float = 25f          // 25 is the RenderScript maximum
) : Transformation {

    override val cacheKey = "${BlurTransformation::class.java.name}-$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Work on an ARGB_8888 mutable copy — RenderScript requires this format
        val bitmap = input.copy(Bitmap.Config.ARGB_8888, true)

        val rs = RenderScript.create(context)
        try {
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            val inAlloc  = Allocation.createFromBitmap(
                rs, bitmap,
                Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_SCRIPT
            )
            val outAlloc = Allocation.createTyped(rs, inAlloc.type)

            blur.setRadius(radius.coerceIn(0.0001f, 25f))
            blur.setInput(inAlloc)
            blur.forEach(outAlloc)
            outAlloc.copyTo(bitmap)

            inAlloc.destroy()
            outAlloc.destroy()
            blur.destroy()
        } finally {
            rs.destroy()
        }

        return bitmap
    }
}
