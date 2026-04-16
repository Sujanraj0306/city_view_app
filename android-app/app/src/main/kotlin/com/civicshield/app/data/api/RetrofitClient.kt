package com.civicshield.app.data.api

import android.content.Context
import com.civicshield.app.BuildConfig
import com.civicshield.app.data.local.AuthStore
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    @Volatile
    private var instance: ApiService? = null

    fun create(context: Context): ApiService {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val built = buildInstance(context.applicationContext)
            instance = built
            return built
        }
    }

    private fun buildInstance(appContext: Context): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(AuthStore(appContext)))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val baseUrl = BuildConfig.BASE_URL.trimEnd('/') + "/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
