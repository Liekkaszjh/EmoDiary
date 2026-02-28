package com.example.emotiondiary

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.emotiondiary.auth.LoginFragment
import com.example.emotiondiary.auth.RegisterFragment
import com.example.emotiondiary.databinding.ActivityAuthBinding
import com.example.emotiondiary.storage.SessionManager

class AuthActivity : AppCompatActivity() {
    private enum class Tab { LOGIN, REGISTER }

    private lateinit var binding: ActivityAuthBinding
    private lateinit var sessionManager: SessionManager
    private val loginFragment = LoginFragment()
    private val registerFragment = RegisterFragment()
    private var currentTab = Tab.LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sessionManager = SessionManager(this)

        if (sessionManager.getToken().isNotBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding.btnLoginTab.setOnClickListener {
            if (currentTab == Tab.LOGIN) {
                loginFragment.submitLogin()
            } else {
                showLogin()
            }
        }
        binding.btnRegisterTab.setOnClickListener {
            if (currentTab == Tab.REGISTER) {
                registerFragment.submitRegister()
            } else {
                showRegister()
            }
        }
        showLogin()
    }

    private fun showLogin() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.authContainer, loginFragment)
            .commit()
        currentTab = Tab.LOGIN
        updateTabsUi()
    }

    private fun showRegister() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.authContainer, registerFragment)
            .commit()
        currentTab = Tab.REGISTER
        updateTabsUi()
    }

    private fun updateTabsUi() {
        val loginSelected = currentTab == Tab.LOGIN
        binding.btnLoginTab.alpha = if (loginSelected) 1f else 0.75f
        binding.btnRegisterTab.alpha = if (loginSelected) 0.75f else 1f
    }
}
