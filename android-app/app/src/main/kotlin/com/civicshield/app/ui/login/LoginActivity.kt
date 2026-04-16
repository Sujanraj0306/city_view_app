package com.civicshield.app.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.civicshield.app.data.api.ApiService
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.local.AuthStore
import com.civicshield.app.data.model.LoginRequest
import com.civicshield.app.databinding.ActivityLoginBinding
import com.civicshield.app.ui.admin.AdminHomeActivity
import com.civicshield.app.ui.common.LottieUrls
import com.civicshield.app.ui.user.UserHomeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authStore: AuthStore
    private lateinit var api: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authStore = AuthStore(applicationContext)
        api = RetrofitClient.create(applicationContext)

        binding.lottieShield.apply {
            setFailureListener { _ -> /* broken URL: silently leave blank */ }
            setAnimationFromUrl(LottieUrls.LOGIN_SHIELD)
            playAnimation()
        }

        lifecycleScope.launch {
            authStore.currentRole()?.let { navigateByRole(it) }
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLogin.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.login(LoginRequest(username, password))
                }
                authStore.save(response.accessToken, response.role, response.userId)
                navigateByRole(response.role)
            } catch (e: Exception) {
                binding.btnLogin.isEnabled = true
                binding.progress.visibility = android.view.View.GONE
                Toast.makeText(
                    this@LoginActivity,
                    "Login failed: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun navigateByRole(role: String) {
        val next = if (role == "admin") AdminHomeActivity::class.java
                   else UserHomeActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
