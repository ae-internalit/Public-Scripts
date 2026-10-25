package com.doubletap.screenshot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val items: List<ScreenshotItem>,
    private val listener: OnItemClickListener
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onClick(item: ScreenshotItem)
        fun onLongClick(item: ScreenshotItem)
        fun onSelectionChanged(count: Int)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.screenshotThumbnail)
        val dateText: TextView = view.findViewById(R.id.screenshotDate)
        val checkMark: View = view.findViewById(R.id.checkMark)
    }

    var selectionMode = false
    val selected = linkedSetOf<Long>()

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    /** Ticks one item, for the long-press that turns selection on. */
    fun select(id: Long) {
        selected.add(id)
        notifyDataSetChanged()
    }

    fun selectedUris(): List<android.net.Uri> =
        items.filter { it.id in selected }.map { it.uri }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        ThumbnailLoader.load(holder.thumbnail, item.id, item.uri)

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        holder.dateText.text = dateFormat.format(Date(item.date * 1000))

        holder.checkMark.visibility =
            if (selectionMode && item.id in selected) View.VISIBLE else View.GONE
        holder.itemView.alpha =
            if (selectionMode && item.id !in selected) 0.45f else 1f

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                if (!selected.remove(item.id)) selected.add(item.id)
                notifyItemChanged(holder.bindingAdapterPosition)
                listener.onSelectionChanged(selected.size)
            } else {
                listener.onClick(item)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) listener.onLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size
}
