package com.pure.crosshair

/**
 * The bundled crosshair artwork, in sheet order (left to right, top to bottom).
 *
 * Entry 0 is also the launcher icon and the floating button icon.
 * To add your own: drop a transparent PNG into res/drawable-nodpi and add it to this list.
 */
object Catalog {

    val ids = intArrayOf(
        R.drawable.ch_01,
        R.drawable.ch_02,
        R.drawable.ch_03,
        R.drawable.ch_04,
        R.drawable.ch_05,
        R.drawable.ch_06,
        R.drawable.ch_07,
        R.drawable.ch_08,
        R.drawable.ch_09,
        R.drawable.ch_10,
        R.drawable.ch_11,
        R.drawable.ch_12,
        R.drawable.ch_13,
        R.drawable.ch_14,
        R.drawable.ch_15,
        R.drawable.ch_16,
        R.drawable.ch_17,
        R.drawable.ch_18,
        R.drawable.ch_19,
        R.drawable.ch_20,
        R.drawable.ch_21,
        R.drawable.ch_22,
        R.drawable.ch_23,
        R.drawable.ch_24,
        R.drawable.ch_25,
        R.drawable.ch_26,
        R.drawable.ch_27,
        R.drawable.ch_28,
        R.drawable.ch_29,
        R.drawable.ch_30,
        R.drawable.ch_31,
        R.drawable.ch_32,
        R.drawable.ch_33,
        R.drawable.ch_34,
        R.drawable.ch_35
    )

    val size: Int get() = ids.size

    fun at(index: Int): Int = ids[index.coerceIn(0, ids.lastIndex)]
}
