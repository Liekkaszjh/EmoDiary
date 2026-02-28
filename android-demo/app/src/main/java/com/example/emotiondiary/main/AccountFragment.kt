package com.example.emotiondiary.main

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.emotiondiary.AuthActivity
import com.example.emotiondiary.R
import com.example.emotiondiary.data.UpdateProfileRequest
import com.example.emotiondiary.databinding.FragmentAccountBinding
import com.example.emotiondiary.network.RetrofitClient
import com.example.emotiondiary.storage.SessionManager
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {
    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    private val avatarOptions = listOf("user1", "user2", "user3", "user4")
    private var currentAvatar: String = "user1"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val session = SessionManager(requireContext())
        val api = RetrofitClient.api(session)

        currentAvatar = normalizeAvatar(session.getAvatar())
        renderAvatar(currentAvatar)

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.me() }.onSuccess {
                binding.tvNickname.text = it.nickname
                currentAvatar = normalizeAvatar(it.avatar_url)
                renderAvatar(currentAvatar)
                session.saveUserId(it.id)
                session.saveNickname(it.nickname)
                session.saveAvatar(currentAvatar)
            }
        }

        binding.btnEditAvatar.setOnClickListener {
            showAvatarPickerDialog(
                onPick = { selected, dialog ->
                    val nickname = binding.tvNickname.text.toString().ifBlank { getString(R.string.default_user) }
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            api.updateMe(
                                UpdateProfileRequest(
                                    nickname = nickname,
                                    avatar_url = "sys:$selected",
                                )
                            )
                        }.onSuccess {
                            currentAvatar = normalizeAvatar(it.avatar_url)
                            renderAvatar(currentAvatar)
                            binding.tvNickname.text = it.nickname
                            session.saveAvatar(currentAvatar)
                            session.saveNickname(it.nickname)
                            dialog.dismiss()
                        }
                    }
                }
            )
        }

        binding.btnEditProfile.setOnClickListener {
            val input = EditText(requireContext())
            input.setText(binding.tvNickname.text)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_edit_nickname)
                .setView(input)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isBlank()) return@setPositiveButton
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            api.updateMe(
                                UpdateProfileRequest(
                                    nickname = newName,
                                    avatar_url = "sys:$currentAvatar",
                                )
                            )
                        }.onSuccess {
                            binding.tvNickname.text = it.nickname
                            currentAvatar = normalizeAvatar(it.avatar_url)
                            renderAvatar(currentAvatar)
                            session.saveNickname(it.nickname)
                            session.saveAvatar(currentAvatar)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnLogout.setOnClickListener {
            session.clear()
            startActivity(Intent(requireContext(), AuthActivity::class.java))
            requireActivity().finish()
        }
    }

    private fun normalizeAvatar(raw: String?): String {
        if (raw.isNullOrBlank()) return "user1"
        val value = when {
            raw.startsWith("sys:") -> raw.removePrefix("sys:")
            raw.startsWith("emoji:") -> "user1"
            else -> raw
        }
        return if (value in avatarOptions) value else "user1"
    }

    private fun renderAvatar(avatar: String) {
        val resId = when (avatar) {
            "user2" -> R.drawable.avatar_user2
            "user3" -> R.drawable.avatar_user3
            "user4" -> R.drawable.avatar_user4
            else -> R.drawable.avatar_user1
        }
        binding.tvAvatar.setImageResource(resId)
    }

    private fun showAvatarPickerDialog(
        onPick: (selected: String, dialog: AlertDialog) -> Unit
    ) {
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
                alpha = if (key == currentAvatar) 1f else 0.95f
                setOnClickListener {
                    onPick(key, dialog)
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
