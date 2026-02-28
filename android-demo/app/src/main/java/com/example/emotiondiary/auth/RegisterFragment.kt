package com.example.emotiondiary.auth

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.emotiondiary.MainActivity
import com.example.emotiondiary.R
import com.example.emotiondiary.data.RegisterRequest
import com.example.emotiondiary.data.UpdateProfileRequest
import com.example.emotiondiary.databinding.FragmentRegisterBinding
import com.example.emotiondiary.network.RetrofitClient
import com.example.emotiondiary.storage.SessionManager
import kotlinx.coroutines.launch

class RegisterFragment : Fragment() {
    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!

    private val avatarOptions = listOf("user1", "user2", "user3", "user4")
    private var selectedAvatar = "user1"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvError.text = ""
        renderAvatar(selectedAvatar)
        binding.btnChooseAvatar.setOnClickListener {
            showAvatarPickerDialog { picked ->
                selectedAvatar = picked
                renderAvatar(selectedAvatar)
            }
        }
    }

    fun submitRegister() {
        val binding = _binding ?: return
        val session = SessionManager(requireContext())
        val api = RetrofitClient.api(session)
        val username = binding.etUsername.text.toString().trim()
        val nickname = binding.etNickname.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isBlank() || nickname.isBlank() || password.isBlank()) {
            binding.tvError.text = getString(R.string.error_register_input)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.register(RegisterRequest(username, password, nickname)) }
                .onSuccess { token ->
                    session.saveToken(token.access_token)

                    runCatching { RetrofitClient.api(session).me() }.onSuccess { me ->
                        session.saveUserId(me.id)
                        runCatching {
                            RetrofitClient.api(session).updateMe(
                                UpdateProfileRequest(
                                    nickname = nickname,
                                    avatar_url = "sys:$selectedAvatar",
                                )
                            )
                        }.onSuccess { updated ->
                            session.saveNickname(updated.nickname)
                            session.saveAvatar(selectedAvatar)
                        }.onFailure {
                            session.saveNickname(nickname)
                            session.saveAvatar(selectedAvatar)
                        }
                    }.onFailure {
                        session.saveNickname(nickname)
                        session.saveAvatar(selectedAvatar)
                    }

                    startActivity(Intent(requireContext(), MainActivity::class.java))
                    requireActivity().finish()
                }
                .onFailure { e ->
                    binding.tvError.text = e.message ?: getString(R.string.error_register_failed)
                }
        }
    }

    private fun renderAvatar(avatar: String) {
        val resId = when (avatar) {
            "user2" -> R.drawable.avatar_user2
            "user3" -> R.drawable.avatar_user3
            "user4" -> R.drawable.avatar_user4
            else -> R.drawable.avatar_user1
        }
        binding.ivRegisterAvatar.setImageResource(resId)
    }

    private fun showAvatarPickerDialog(onPick: (String) -> Unit) {
        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        val grid = GridLayout(requireContext()).apply {
            columnCount = 2
            rowCount = 2
            useDefaultMargins = true
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.choose_avatar)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        avatarOptions.forEach { key ->
            val image = ImageView(requireContext()).apply {
                val lp = GridLayout.LayoutParams().apply {
                    width = dp(92)
                    height = dp(92)
                }
                layoutParams = lp
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = resources.getDrawable(R.drawable.bg_avatar_thumb, null)
                clipToOutline = true
                setImageResource(
                    when (key) {
                        "user2" -> R.drawable.avatar_user2
                        "user3" -> R.drawable.avatar_user3
                        "user4" -> R.drawable.avatar_user4
                        else -> R.drawable.avatar_user1
                    }
                )
                alpha = if (key == selectedAvatar) 1f else 0.95f
                setOnClickListener {
                    onPick(key)
                    dialog.dismiss()
                }
            }
            grid.addView(image)
        }
        dialogView.addView(grid)
        dialog.show()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
