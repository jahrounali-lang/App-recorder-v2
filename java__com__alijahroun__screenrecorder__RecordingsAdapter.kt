package com.alijahroun.screenrecorder

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateMs: Long,
    val durationMs: Long,
    val size: Long
)

class RecordingsAdapter(
    private val onOpen: (Recording) -> Unit,
    private val onShare: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    private var items: List<Recording> = emptyList()

    fun submit(list: List<Recording>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val open: View = view.findViewById(R.id.btnOpen)
        val share: View = view.findViewById(R.id.btnShare)
        val delete: View = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(r.dateMs))
        val duration = DateUtils.formatElapsedTime(r.durationMs / 1000)
        holder.name.text = r.name
        holder.meta.text = "$date • $duration • ${Formatter.formatShortFileSize(ctx, r.size)}"
        holder.open.setOnClickListener { onOpen(r) }
        holder.share.setOnClickListener { onShare(r) }
        holder.delete.setOnClickListener { onDelete(r) }
    }
}
