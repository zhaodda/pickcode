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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CodeRecordAdapter(
    private val onFavoriteClick: (CodeRecord) -> Unit,
    private val onDeleteClick: (CodeRecord) -> Unit
) : ListAdapter<CodeRecord, CodeRecordAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CodeRecord>() {
            override fun areItemsTheSame(a: CodeRecord, b: CodeRecord) = a.id == b.id
            override fun areContentsTheSame(a: CodeRecord, b: CodeRecord) = a == b
        }
        private val SDF = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    inner class VH(val binding: ItemCodeRecordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCodeRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = getItem(position)
        with(holder.binding) {
            tvCode.text = record.code
            tvType.text = "${record.codeType.emoji} ${record.codeType.label}"
            tvTime.text = SDF.format(Date(record.timestamp))

            // 驿站地址：仅快递类型且有地址时显示
            if (record.address.isNotEmpty()) {
                tvAddress.text = "📍 ${record.address}"
                tvAddress.visibility = android.view.View.VISIBLE
            } else {
                tvAddress.visibility = android.view.View.GONE
            }

            btnFavorite.isSelected = record.isFavorite
            btnFavorite.setOnClickListener { onFavoriteClick(record) }
            btnDelete.setOnClickListener { onDeleteClick(record) }
            root.setOnClickListener {
                val ctx = root.context
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("验证码", record.code))
                Toast.makeText(ctx, "已复制：${record.code}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
