package com.bicilona.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import com.bicilona.R
import com.google.android.libraries.places.api.model.AutocompletePrediction

class PlaceSuggestionAdapter(
    context: Context
) : ArrayAdapter<AutocompletePrediction>(context, R.layout.item_place_suggestion) {

    private var predictions: List<AutocompletePrediction> = emptyList()

    fun setPredictions(newPredictions: List<AutocompletePrediction>) {
        predictions = newPredictions
        notifyDataSetChanged()
    }

    override fun getCount(): Int = predictions.size

    override fun getItem(position: Int): AutocompletePrediction = predictions[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_place_suggestion, parent, false)

        val prediction = predictions[position]
        view.findViewById<TextView>(R.id.tvPrimary).text =
            prediction.getPrimaryText(null)
        view.findViewById<TextView>(R.id.tvSecondary).text =
            prediction.getSecondaryText(null)

        return view
    }

    // Prevent the ArrayAdapter filter from interfering
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
            values = predictions
            count = predictions.size
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence {
            return (resultValue as? AutocompletePrediction)
                ?.getPrimaryText(null) ?: ""
        }
    }
}
