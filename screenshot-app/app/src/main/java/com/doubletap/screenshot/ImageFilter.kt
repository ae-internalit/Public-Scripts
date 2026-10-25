package com.doubletap.screenshot

/**
 * One-tap colour adjustments, as plain colour matrices.
 *
 * A colour matrix is four rows of five numbers, one row per channel, applied as
 * `out = row[0]*R + row[1]*G + row[2]*B + row[3]*A + row[4]`, with the offset in
 * 0..255. Keeping the maths here as FloatArray rather than android.graphics
 * means it can be unit tested; the editor wraps the result in a ColorMatrix.
 *
 * This is deliberately a short list. Levels, curves and masking belong in a
 * photo editor, and the phone already has one.
 */
enum class ImageFilter(val label: String) {
    NONE("Original"),
    MONO("Black & white"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    BRIGHT("Brighter"),
    CONTRAST("More contrast");

    /** Null means "leave the pixels alone", so no work is done for Original. */
    fun matrix(): FloatArray? = when (this) {
        NONE -> null
        MONO -> saturation(0f)
        VIVID -> saturation(1.5f)
        WARM -> channelScale(1.12f, 1f, 0.9f)
        COOL -> channelScale(0.9f, 1f, 1.12f)
        BRIGHT -> brightness(32f)
        CONTRAST -> contrast(1.3f)
    }

    companion object {

        // Rec. 709 luminance, the same weights ColorMatrix.setSaturation uses.
        private const val LR = 0.213f
        private const val LG = 0.715f
        private const val LB = 0.072f

        fun saturation(s: Float): FloatArray {
            val inv = 1f - s
            return floatArrayOf(
                LR * inv + s, LG * inv, LB * inv, 0f, 0f,
                LR * inv, LG * inv + s, LB * inv, 0f, 0f,
                LR * inv, LG * inv, LB * inv + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        }

        fun brightness(offset: Float): FloatArray = floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )

        /** Scales around mid-grey, so a contrast bump does not also brighten. */
        fun contrast(c: Float): FloatArray {
            val t = (1f - c) * 127.5f
            return floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        }

        fun channelScale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        /**
         * Applies a matrix to one colour, clamped to 0..255. Used by the tests;
         * on a phone the same arithmetic happens inside Skia.
         */
        fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
            fun row(i: Int) = (m[i] * r + m[i + 1] * g + m[i + 2] * b + m[i + 4])
                .coerceIn(0f, 255f)
            return Triple(row(0), row(5), row(10))
        }
    }
}
