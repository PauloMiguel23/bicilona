package com.bicilona.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageButton
import android.widget.TextView
import com.bicilona.R
import com.bicilona.data.db.FavoritePlace
import com.google.android.libraries.places.api.model.AutocompletePrediction

/**
 * Dropdown item: either a favorite or a place suggestion
 */
sealed class DropdownItem {
    data class Favorite(val place: FavoritePlace) : DropdownItem()
    data class Suggestion(val prediction: AutocompletePrediction) : DropdownItem()
}

class DestinationDropdownAdapter(
    context: Context,
    private val onDeleteFavorite: (FavoritePlace) -> Unit
) : ArrayAdapter<DropdownItem>(context, 0) {

    var onItemClickListener: ((DropdownItem) -> Unit)? = null

    private var items: List<DropdownItem> = emptyList()

    fun setData(favorites: List<FavoritePlace>, predictions: List<AutocompletePrediction>) {
        items = favorites.map { DropdownItem.Favorite(it) } +
                predictions.map { DropdownItem.Suggestion(it) }
        notifyDataSetChanged()
    }

    fun showFavoritesOnly(favorites: List<FavoritePlace>) {
        items = favorites.map { DropdownItem.Favorite(it) }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): DropdownItem = items[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = items[position]
        return when (item) {
            is DropdownItem.Favorite -> {
                val view = convertView?.takeIf { it.tag == "fav" }
                    ?: LayoutInflater.from(context).inflate(R.layout.item_dropdown_favorite, parent, false).also { it.tag = "fav" }
                view.findViewById<TextView>(R.id.tvDropdownFavName).text = item.place.name
                view.findViewById<ImageButton>(R.id.btnDropdownDeleteFav).setOnClickListener {
                    onDeleteFavorite(item.place)
                }
                view.setOnClickListener {
                    // Invoke the onItemClickListener callback with the item
                    onItemClickListener?.invoke(item)
                }
                view
            }
            is DropdownItem.Suggestion -> {
                val view = convertView?.takeIf { it.tag == "sug" }
                    ?: LayoutInflater.from(context).inflate(R.layout.item_place_suggestion, parent, false).also { it.tag = "sug" }
                view.findViewById<TextView>(R.id.tvPrimary).text = item.prediction.getPrimaryText(null)
                view.findViewById<TextView>(R.id.tvSecondary).text = item.prediction.getSecondaryText(null)
                view
            }
        }
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
            values = items
            count = items.size
        }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }
        override fun convertResultToString(resultValue: Any?): CharSequence {
            return when (resultValue) {
                is DropdownItem.Favorite -> resultValue.place.name
                is DropdownItem.Suggestion -> resultValue.prediction.getPrimaryText(null)
                else -> ""
            }
        }
    }
}
