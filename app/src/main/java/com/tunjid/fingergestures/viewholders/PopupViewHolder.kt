/*
 * Copyright (c) 2017, 2018, 2019 Adetunji Dahunsi.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tunjid.fingergestures.viewholders

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.tunjid.androidx.recyclerview.adapterOf
import com.tunjid.androidx.recyclerview.gridLayoutManager
import com.tunjid.androidx.view.util.inflate
import com.tunjid.fingergestures.App
import com.tunjid.fingergestures.PopUpGestureConsumer
import com.tunjid.fingergestures.R
import com.tunjid.fingergestures.activities.MainActivity
import com.tunjid.fingergestures.adapters.AppAdapterListener
import com.tunjid.fingergestures.fragments.ActionFragment
import com.tunjid.fingergestures.gestureconsumers.GestureConsumer

class PopupViewHolder(itemView: View, items: MutableList<Int>, listener: AppAdapterListener) : DiffViewHolder<Int>(itemView, items, listener) {

    override val sizeCacheKey: String
        get() = javaClass.simpleName

    override val listSupplier: () -> List<Int>
        get() = { PopUpGestureConsumer.instance.list }

    init {

        itemView.findViewById<View>(R.id.add).setOnClickListener {
            when {
                !App.canWriteToSettings() -> AlertDialog.Builder(itemView.context).setMessage(R.string.permission_required).show()
                !PopUpGestureConsumer.instance.hasAccessibilityButton() -> AlertDialog.Builder(itemView.context).setMessage(R.string.popup_prompt).show()
                else -> listener.showBottomSheetFragment(ActionFragment.popUpInstance())
            }
        }

        val title = itemView.findViewById<TextView>(R.id.title)

        title.setText(R.string.popup_title)
        title.setOnClickListener {
            AlertDialog.Builder(itemView.context)
                    .setMessage(R.string.popup_description)
                    .show()
        }
    }

    override fun bind() {
        super.bind()

        diff()
        if (!App.canWriteToSettings()) listener.requestPermission(MainActivity.SETTINGS_CODE)
    }

    override fun setupRecyclerView(recyclerView: RecyclerView) = recyclerView.run {
        layoutManager = gridLayoutManager(3)
        adapter = adapterOf(
                itemsSource = ::items,
                viewHolderCreator = { viewGroup, _ ->
                    ActionViewHolder(
                            showsText = true,
                            itemView = viewGroup.inflate(R.layout.viewholder_action_horizontal),
                            clickListener = ::onActionClicked
                    )
                },
                viewHolderBinder = { holder, item, _ -> holder.bind(item) }
        )
    }

    private fun onActionClicked(@GestureConsumer.GestureAction action: Int) {
        val buttonManager = PopUpGestureConsumer.instance

        val builder = AlertDialog.Builder(itemView.context)

        when {
            !App.canWriteToSettings() -> builder.setMessage(R.string.permission_required)
            !buttonManager.hasAccessibilityButton() -> builder.setMessage(R.string.popup_prompt)
            else -> builder.setTitle(R.string.popup_remove)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        buttonManager.removeFromSet(action)
                        bind()
                    }
                    .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
        }

        builder.show()
    }
}