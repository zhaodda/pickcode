package com.pickcode.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.databinding.FragmentCodeListBinding
import com.pickcode.app.ui.adapter.CodeRecordAdapter
import com.pickcode.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 取件码列表 Fragment（ViewPager2 每页承载）
 *
 * @param isPickedUp 为 true 时显示"已取件"列表，false 时显示"未取件"列表
 */
class CodeListFragment : Fragment() {

    companion object {
        private const val ARG_PICKED_UP = "arg_picked_up"

        fun newInstance(isPickedUp: Boolean): CodeListFragment {
            return CodeListFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_PICKED_UP, isPickedUp)
                }
            }
        }
    }

    private var _binding: FragmentCodeListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private val adapter: CodeRecordAdapter by lazy {
        CodeRecordAdapter(
            onFavoriteClick = { viewModel.toggleFavorite(it) },
            onDeleteClick   = { viewModel.delete(it) },
            onPickedUpClick = { viewModel.togglePickedUp(it) }
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCodeListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager =
            LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        val isPickedUp = arguments?.getBoolean(ARG_PICKED_UP) ?: false

        lifecycleScope.launch {
            val flow = if (isPickedUp)
                viewModel.pickedUpRecords
            else
                viewModel.notPickedUpRecords
            flow.collectLatest { list ->
                adapter.submitList(list)
                binding.layoutEmpty.isGone = list.isNotEmpty()
                binding.recyclerView.isVisible = list.isNotEmpty()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
