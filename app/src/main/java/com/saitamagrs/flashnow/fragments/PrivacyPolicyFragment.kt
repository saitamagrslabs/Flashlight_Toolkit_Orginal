package com.saitamagrs.flashnow.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.saitamagrs.flashnow.MainActivity
import com.saitamagrs.flashnow.databinding.FragmentPrivacyPolicyBinding
import com.saitamagrs.flashnow.utils.AppConstants

class PrivacyPolicyFragment : Fragment() {

    private var _binding: FragmentPrivacyPolicyBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrivacyPolicyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnOpenPolicy.setOnClickListener {
            openPrivacyPolicy()
        }
    }

    private fun openPrivacyPolicy() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.PRIVACY_POLICY_URL))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Handle case where no browser is installed or URL is malformed
        }
    }

    override fun onResume() {
        super.onResume()
    //    (activity as? MainActivity)?.updateToolbarForFragment(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}