package com.teslablelab.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.teslablelab.R
import com.teslablelab.data.repository.BleLogEntry
import com.teslablelab.data.repository.LogLevel
import java.text.SimpleDateFormat
import java.util.*

class LogAdapter : ListAdapter<BleLogEntry, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestamp: TextView = itemView.findViewById(R.id.logTimestamp)
        private val level: TextView = itemView.findViewById(R.id.logLevel)
        private val message: TextView = itemView.findViewById(R.id.logMessage)

        fun bind(entry: BleLogEntry) {
            timestamp.text = timeFormat.format(Date(entry.timestamp))

            level.text = when (entry.level) {
                LogLevel.DEBUG -> "D"
                LogLevel.INFO -> "I"
                LogLevel.WARNING -> "W"
                LogLevel.ERROR -> "E"
            }

            level.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    when (entry.level) {
                        LogLevel.DEBUG -> R.color.text_secondary
                        LogLevel.INFO -> R.color.primary
                        LogLevel.WARNING -> R.color.warning
                        LogLevel.ERROR -> R.color.error
                    }
                )
            )

            message.text = entry.message
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<BleLogEntry>() {
        override fun areItemsTheSame(oldItem: BleLogEntry, newItem: BleLogEntry): Boolean {
            return oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: BleLogEntry, newItem: BleLogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
