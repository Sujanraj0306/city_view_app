package com.civicshield.app.ui.admin

import android.graphics.drawable.Animatable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.civicshield.app.R
import com.civicshield.app.databinding.ActivityAdminHomeBinding
import com.civicshield.app.ui.common.RssFragment

class AdminHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(AllCasesFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_map) (item.icon as? Animatable)?.start()
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_all_cases -> AllCasesFragment()
                R.id.nav_map -> AdminMapFragment()
                R.id.nav_analytics -> AnalyticsFragment()
                R.id.nav_rss -> RssFragment()
                else -> return@setOnItemSelectedListener false
            }
            showFragment(fragment)
            true
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
