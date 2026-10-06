package com.example.aimeshvision.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.aimeshvision.R

/**
 * Multi-image swipe gallery adapter (ViewPager2).
 *
 * v2: DiffUtil-based updates instead of notifyDataSetChanged (F11), and
 * proper view recycling via the layout inflater.
 */
class ImagePagerAdapter : RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

    private val bitmaps = mutableListOf<Bitmap>()

    private data class Diff(val oldList: List<Bitmap>, val newList: List<Bitmap>)

    fun setImages(images: List<Bitmap>) {
        val old = bitmaps.toList()
        bitmaps.clear()
        bitmaps.addAll(images)
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = images.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                old[oldPos] == images[newPos]      // same instance = same image
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = true
        }).dispatchUpdatesTo(this)
    }

    fun getImage(position: Int): Bitmap? = bitmaps.getOrNull(position)

    override fun getItemCount(): Int = bitmaps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val imageView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_page, parent, false) as ImageView
        return ImageViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        (holder.itemView as ImageView).setImageBitmap(bitmaps[position])
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
