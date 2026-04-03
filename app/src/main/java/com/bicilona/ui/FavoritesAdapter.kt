package com.bicilona.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bicilona.R
import com.bicilona.data.db.FavoritePlace

class FavoritesAdapter(
    private val onClick: (FavoritePlace) -> Unit,
    private val onDelete: (FavoritePlace) -> Unit
) : ListAdapter<FavoritePlace, FavoritesAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FavoritePlace>() {
            override fun areItemsTheSame(a: FavoritePlace, b: FavoritePlace) = a.id == b.id
            override fun areContentsTheSame(a: FavoritePlace, b: FavoritePlace) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFavName)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteFav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fav = getItem(position)
        holder.tvName.text = fav.name
        holder.itemView.setOnClickListener { onClick(fav) }
        holder.btnDelete.setOnClickListener { onDelete(fav) }
    }
}
