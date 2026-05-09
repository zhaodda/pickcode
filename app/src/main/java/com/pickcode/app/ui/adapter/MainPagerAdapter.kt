package com.pickcode.app.ui.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pickcode.app.ui.fragment.CodeListFragment

/**
 * ViewPager2 适配器：两页
 *  - 0 = 未取件
 *  - 1 = 已取件
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> CodeListFragment.newInstance(false)  // 未取件
            1 -> CodeListFragment.newInstance(true)   // 已取件
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
