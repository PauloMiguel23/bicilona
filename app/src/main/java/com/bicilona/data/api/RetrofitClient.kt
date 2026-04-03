package com.bicilona.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private const val BICING_BASE_URL =
        "https://barcelona.publicbikesystem.net/customer/gbfs/v2/en/"

    private const val DIRECTIONS_BASE_URL =
        "https://maps.googleapis.com/maps/api/directions/"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    val bicingApi: BicilonaApi by lazy {
        Retrofit.Builder()
            .baseUrl(BICING_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BicilonaApi::class.java)
    }

    val directionsApi: DirectionsApi by lazy {
        Retrofit.Builder()
            .baseUrl(DIRECTIONS_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DirectionsApi::class.java)
    }
}
