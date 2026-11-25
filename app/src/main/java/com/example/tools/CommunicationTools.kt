package com.example.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * কল, SMS, কন্ট্যাক্ট সার্চ এবং WhatsApp চ্যাট সংক্রান্ত টুল এক্সিকিউটর।
 */
object CommunicationTools {

    fun searchContacts(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) {
            return ToolResult(false, "কন্ট্যাক্ট খোঁজার জন্য নাম বা নম্বর উল্লেখ করুন।")
        }
        val matches = ContactAndPhoneHelper.searchContacts(ctx.appContext, query)
        val arr = JSONArray()
        matches.forEach { m ->
            arr.put(JSONObject().apply {
                put("name", m.name)
                put("phone_number", m.phoneNumber)
                put("type", m.typeLabel)
            })
        }
        return if (matches.isEmpty()) {
            ToolResult(true, "'$query' নামে কোনো কন্ট্যাক্ট পাওয়া যায়নি।", JSONObject().put("contacts", arr))
        } else {
            ToolResult(true, "${matches.size}টি কন্ট্যাক্ট পাওয়া গেছে।", JSONObject().put("contacts", arr))
        }
    }

    @SuppressLint("MissingPermission")
    fun makeCall(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val rawNumber = args.optString("phone_number", "").trim()
        val contactName = args.optString("contact_name", "").trim()
        val number = ContactAndPhoneHelper.normalizePhoneNumber(rawNumber)

        if (number.isBlank()) {
            return ToolResult(false, "কল করার জন্য বৈধ ফোন নম্বর পাওয়া যায়নি।")
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // ডুয়াল সিম পছন্দ থাকলে TelecomManager-এর মাধ্যমে PhoneAccountHandle সেট করার চেষ্টা
        if (ctx.preferredSimSlot >= 0) {
            try {
                val telecom = ctx.appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val accounts = telecom?.callCapablePhoneAccounts
                if (accounts != null && ctx.preferredSimSlot < accounts.size) {
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[ctx.preferredSimSlot])
                }
            } catch (_: SecurityException) {
                // READ_PHONE_STATE না থাকলে ডিফল্ট সিম ব্যবহার করবে
            } catch (_: Exception) {
            }
        }

        return try {
            ctx.appContext.startActivity(intent)
            val label = if (contactName.isNotBlank()) "$contactName ($number)" else number
            ToolResult(
                success = true,
                message = "$label-এ কল করা হচ্ছে।",
                data = JSONObject().put("phone_number", number).put("contact_name", contactName)
            )
        } catch (e: Exception) {
            ToolResult(false, "কল শুরু করা যায়নি: ${e.localizedMessage ?: "ডিভাইসে কলিং অ্যাপ নেই"}")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendSms(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val rawNumber = args.optString("phone_number", "").trim()
        val contactName = args.optString("contact_name", "").trim()
        val message = args.optString("message", "").trim()
        val number = ContactAndPhoneHelper.normalizePhoneNumber(rawNumber)

        if (number.isBlank() || message.isBlank()) {
            return ToolResult(false, "SMS পাঠানোর জন্য ফোন নম্বর এবং মেসেজের লেখা দুটোই প্রয়োজন।")
        }

        return try {
            val smsManager = resolveSmsManager(ctx.appContext, ctx.preferredSimSlot)
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, message, null, null)
            }
            val label = if (contactName.isNotBlank()) "$contactName ($number)" else number
            ToolResult(
                success = true,
                message = "$label-এ SMS পাঠানো হয়েছে।",
                data = JSONObject().put("phone_number", number)
            )
        } catch (e: Exception) {
            ToolResult(false, "SMS পাঠানো যায়নি: ${e.localizedMessage ?: "অজানা ত্রুটি"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveSmsManager(context: Context, preferredSimSlot: Int): SmsManager {
        if (preferredSimSlot >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val info = subMgr?.getActiveSubscriptionInfoForSimSlotIndex(preferredSimSlot)
                    if (info != null) {
                        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java).createForSubscriptionId(info.subscriptionId)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getSmsManagerForSubscriptionId(info.subscriptionId)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    fun openWhatsAppChat(ctx: ToolExecutionContext, args: JSONObject): ToolResult {
        val rawNumber = args.optString("phone_number", "").trim()
        val message = args.optString("message", "").trim()
        val waNumber = ContactAndPhoneHelper.toWhatsAppNumber(rawNumber)

        if (waNumber.isBlank()) {
            return ToolResult(false, "WhatsApp চ্যাট খোলার জন্য বৈধ ফোন নম্বর দিন।")
        }

        val pm = ctx.appContext.packageManager
        val hasWhatsApp = isPackageInstalled(pm, "com.whatsapp") || isPackageInstalled(pm, "com.whatsapp.w4b")
        if (!hasWhatsApp) {
            return ToolResult(false, "এই ডিভাইসে WhatsApp ইনস্টল করা নেই।")
        }

        val uriBuilder = StringBuilder("https://wa.me/$waNumber")
        if (message.isNotBlank()) {
            uriBuilder.append("?text=").append(Uri.encode(message))
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(
                success = true,
                message = "WhatsApp চ্যাট ($waNumber) খোলা হয়েছে। পাঠাতে অনুগ্রহ করে নিজে Send বাটন চাপুন।",
                data = JSONObject().put("whatsapp_number", waNumber)
            )
        } catch (e: Exception) {
            ToolResult(false, "WhatsApp খোলা যায়নি: ${e.localizedMessage ?: "অজানা ত্রুটি"}")
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
