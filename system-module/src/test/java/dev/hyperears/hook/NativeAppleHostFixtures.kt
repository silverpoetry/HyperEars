package com.miui.circulate.world.headset.ui

import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Minimal native-class fixtures: real Android children and attachment, no mocked binding behavior.
class HeadsetControlAncItemView(context: Context) : LinearLayout(context) {
    init { addView(TextView(context).apply { id = 101 }); addView(ImageView(context).apply { id = 102 }) }
}
class HeadsetSelectItemView(context: Context) : LinearLayout(context) {
    init { addView(TextView(context).apply { id = 103 }); addView(ImageView(context).apply { id = 104 }) }
}
class HeadsetSelectCardView(context: Context) : LinearLayout(context)
