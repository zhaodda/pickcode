package com.pickcode.app.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.databinding.ItemCodeRecordBinding
import com.pickcode.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.core.view.isGone
import androidx.core.view.isVisible

class CodeRecordAdapter(
    private val onFavoriteClick: (CodeRecord) -> Unit,
    private val onDeleteClick: (CodeRecord) -> Unit,
    private val onPickedUpClick: (CodeRecord) -> Unit
) : ListAdapter<CodeRecord, CodeRecordAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCodeRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemCodeRecordBinding) : RecyclerView.ViewHolder(b.root) {
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun bind(record: CodeRecord) {
            b.tvCode.text = record.code
            b.tvType.text = "${record.codeType.emoji} ${record.codeType.label}"
            b.tvTime.text = sdf.format(Date(record.timestamp))

            if (record.address.isNotEmpty()) {
                b.tvAddress.text = "📍 ${record.address}"
                b.tvAddress.isVisible = true
            } else {
                b.tvAddress.isGone = true
            }

            // 已取件状态 UI
            if (record.isPickedUp) {
                (b.root as com.google.android.material.card.MaterialCardView)
                    .setCardBackgroundColor(b.root.context.getColor(R.color.bg_picked_up))
                b.tvCode.setTextColor(b.root.context.getColor(R.color.text_picked_up))
                b.tvPickedUp.isVisible = true
            } else {
                (b.root as com.google.android.material.card.MaterialCardView)
                    .setCardBackgroundColor(b.root.context.getColor(R.color.bg_card))
                b.tvCode.setTextColor(b.root.context.getColor(R.color.text_primary))
                b.tvPickedUp.isGone = true
            }

            b.btnFavorite.isSelected = record.isFavorite
            b.btnFavorite.setOnClickListener { onFavoriteClick(record) }
            b.btnDelete.setOnClickListener { onDeleteClick(record) }

            // 点击卡片 → 切换已取件状态
            b.root.setOnClickListener { onPickedUpClick(record) }

            // 长按卡片 → 复制取件码
            b.root.setOnLongClickListener {
                val ctx = b.root.context
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("取件码", record.code))
                Toast.makeText(ctx, "已复制：${record.code}", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CodeRecord>() {
            override fun areItemsTheSame(a: CodeRecord, b: CodeRecord): Boolean = a.id == b.id
            override fun areContentsTheSame(a: CodeRecord, b: CodeRecord): Boolean = a == b
        }
    }
}
