package com.example.emotiondiary.auth

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.emotiondiary.MainActivity
import com.example.emotiondiary.R
import com.example.emotiondiary.data.LoginRequest
import com.example.emotiondiary.databinding.FragmentLoginBinding
import com.example.emotiondiary.network.RetrofitClient
import com.example.emotiondiary.storage.SessionManager
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvError.text = ""
    }

    fun submitLogin() {
        val binding = _binding ?: return
        val session = SessionManager(requireContext())
        val api = RetrofitClient.api(session)
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isBlank() || password.isBlank()) {
            binding.tvError.text = getString(R.string.error_login_input)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.login(LoginRequest(username, password)) }
                .onSuccess { token ->
                    session.saveToken(token.access_token)
                    runCatching { RetrofitClient.api(session).me() }.onSuccess { me ->
                        session.saveUserId(me.id)
                        session.saveNickname(me.nickname)
                    }
                    startActivity(Intent(requireContext(), MainActivity::class.java))
                    requireActivity().finish()
                }
                .onFailure { e ->
                    binding.tvError.text = e.message ?: getString(R.string.error_login_failed)
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
