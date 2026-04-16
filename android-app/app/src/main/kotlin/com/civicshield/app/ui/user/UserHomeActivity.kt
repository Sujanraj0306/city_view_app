package com.civicshield.app.ui.user

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.civicshield.app.R
import com.civicshield.app.data.local.AuthStore
import com.civicshield.app.databinding.ActivityUserHomeBinding
import com.civicshield.app.ui.agent.AgentActivity
import com.civicshield.app.ui.common.CaseMapFragment
import com.civicshield.app.ui.common.RssFragment
import com.civicshield.app.ui.login.LoginActivity
import kotlinx.coroutines.launch

class UserHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_logout) {
                logout()
                true
            } else false
        }

        binding.fabAgent.setOnClickListener {
            startActivity(Intent(this, AgentActivity::class.java))
        }

        // Bottom nav sits clear of the system gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            showFragment(ReportFragment(), animated = false)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_map) (item.icon as? Animatable)?.start()
            val (fragment, slideUp) = when (item.itemId) {
                R.id.nav_report -> ReportFragment() to true
                R.id.nav_my_cases -> MyCasesFragment() to false
                R.id.nav_map -> CaseMapFragment.newInstance(CaseMapFragment.Mode.USER) to false
                R.id.nav_rss -> RssFragment() to false
                else -> return@setOnItemSelectedListener false
            }
            showFragment(fragment, animated = slideUp)
            true
        }
    }

    private fun showFragment(fragment: Fragment, animated: Boolean) {
        val tx = supportFragmentManager.beginTransaction()
        if (animated) {
            tx.setCustomAnimations(R.anim.slide_in_bottom, R.anim.fade_out)
        }
        tx.replace(R.id.fragment_container, fragment).commit()
    }

    private fun logout() {
        lifecycleScope.launch {
            AuthStore(applicationContext).clear()
            val intent = Intent(this@UserHomeActivity, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }
}
