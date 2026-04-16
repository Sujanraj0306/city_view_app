package com.civicshield.app.data.api

import com.civicshield.app.data.local.AuthStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val authStore: AuthStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { authStore.currentToken() }
        val request = chain.request()
        val withAuth = if (!token.isNullOrEmpty()) {
            request.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else request
        return chain.proceed(withAuth)
    }
}
