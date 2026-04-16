package com.civicshield.app.ui.user

import android.graphics.drawable.Animatable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.civicshield.app.R
import com.civicshield.app.databinding.ActivityUserHomeBinding
import com.civicshield.app.ui.common.CaseMapFragment
import com.civicshield.app.ui.common.RssFragment

class UserHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
}
