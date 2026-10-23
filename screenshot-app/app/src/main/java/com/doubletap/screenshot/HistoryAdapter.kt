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
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.screenshotThumbnail)
        val dateText: TextView = view.findViewById(R.id.screenshotDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.thumbnail.setImageURI(item.uri)
        holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        holder.dateText.text = dateFormat.format(Date(item.date * 1000))

        holder.itemView.setOnClickListener { listener.onClick(item) }
        holder.itemView.setOnLongClickListener {
            listener.onLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size
}
