package com.example.tools

import android.content.Context
import android.provider.ContactsContract

/**
 * বাংলাদেশি ফোন নম্বর (01XXXXXXXXX / +8801XXXXXXXXX) এবং বাংলা-ইংরেজি কন্ট্যাক্ট খোঁজার হেল্পার।
 */
object ContactAndPhoneHelper {

    fun normalizeBengaliDigits(input: String): String {
        val bnDigits = "০১২৩৪৫৬৭৮৯"
        val enDigits = "0123456789"
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val idx = bnDigits.indexOf(ch)
            sb.append(if (idx >= 0) enDigits[idx] else ch)
        }
        return sb.toString()
    }

    /**
     * কল বা SMS-এর জন্য বাংলাদেশি ফোন নম্বর স্ট্যান্ডার্ড ফরম্যাটে আনে।
     */
    fun normalizePhoneNumber(raw: String): String {
        val ascii = normalizeBengaliDigits(raw.trim())
        val cleaned = ascii.replace(Regex("[\\s\\-()]"), "")
        return when {
            cleaned.startsWith("+8801") -> cleaned
            cleaned.startsWith("8801") && cleaned.length == 13 -> "+$cleaned"
            cleaned.startsWith("01") && cleaned.length == 11 -> cleaned
            else -> cleaned
        }
    }

    /**
     * WhatsApp wa.me লিংকের জন্য '+' ছাড়া আন্তর্জাতিক ফরম্যাট (8801XXXXXXXXX)।
     */
    fun toWhatsAppNumber(raw: String): String {
        val norm = normalizePhoneNumber(raw).removePrefix("+")
        return when {
            norm.startsWith("01") && norm.length == 11 -> "88$norm"
            else -> norm
        }
    }

    /**
     * ফোনের কন্ট্যাক্ট লিস্ট থেকে বাংলা ও ইংরেজি উভয় নামে খোঁজে।
     */
    fun searchContacts(context: Context, query: String): List<ContactMatch> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val queryLower = trimmed.lowercase()
        val queryPhonetic = transliterateBengaliToLatin(queryLower)

        val results = mutableListOf<ContactMatch>()
        val seenNumbers = hashSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                val rawNum = cursor.getString(numIdx)?.trim().orEmpty()
                if (name.isBlank() || rawNum.isBlank()) continue

                val nameLower = name.lowercase()
                val namePhonetic = transliterateBengaliToLatin(nameLower)

                val matchesDirect = nameLower.contains(queryLower)
                val matchesPhonetic = queryPhonetic.length >= 2 && (
                    nameLower.contains(queryPhonetic) ||
                        namePhonetic.contains(queryLower) ||
                        namePhonetic.contains(queryPhonetic)
                    )

                if (matchesDirect || matchesPhonetic) {
                    val normalizedNum = normalizePhoneNumber(rawNum)
                    val dedupKey = "${nameLower}_$normalizedNum"
                    if (seenNumbers.add(dedupKey)) {
                        val typeInt = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources,
                            typeInt,
                            ""
                        ).toString()
                        results.add(ContactMatch(name = name, phoneNumber = normalizedNum, typeLabel = typeLabel))
                    }
                }
                if (results.size >= 15) break
            }
        }
        // Nothing matched by substring/phonetics: propose typo/nickname candidates (JARVIS-derived ranking).
        // Candidates only; the caller's picker/confirmation decides, nothing is auto-selected here.
        if (results.isEmpty()) results += fuzzyFallback(context, trimmed)
        return results
    }

    private fun fuzzyFallback(context: Context, query: String): List<ContactMatch> {
        val all = ArrayList<com.example.automation.contacts.NamedNumber>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                val n = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val p = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && all.size < 5000) {
                    val name = c.getString(n)?.trim().orEmpty()
                    val num = c.getString(p)?.trim().orEmpty()
                    if (name.isNotBlank() && num.isNotBlank()) all += com.example.automation.contacts.NamedNumber(name, normalizePhoneNumber(num))
                }
            }
        }
        return com.example.automation.contacts.ContactRanking.rank(query, all)
            .distinctBy { it.contact.name.lowercase() + "_" + it.contact.number }
            .take(5)
            .map { ContactMatch(name = it.contact.name, phoneNumber = it.contact.number) }
    }

    /**
     * বাংলা নাম থেকে সহজ ফোনেটিক ল্যাটিন রূপান্তর (যেমন: "রহিম" -> "rhim", "করিম" -> "krim")
     * যাতে ইংরেজিতে সেভ করা কন্ট্যাক্ট বাংলা উচ্চারণেও খুঁজে পাওয়া যায়।
     */
    private fun transliterateBengaliToLatin(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        val map: Map<Char, String> = mapOf(
            'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "i", 'উ' to "u", 'ঊ' to "u",
            'এ' to "e", 'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou",
            'া' to "a", 'ি' to "i", 'ী' to "i", 'ু' to "u", 'ূ' to "u", 'ে' to "e", 'ো' to "o",
            'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh",
            'চ' to "ch", 'ছ' to "ch", 'জ' to "j", 'ঝ' to "jh",
            'ট' to "t", 'ঠ' to "th", 'ড' to "d", 'ঢ' to "dh",
            'ত' to "t", 'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
            'প' to "p", 'ফ' to "f", 'ব' to "b", 'ভ' to "bh", 'ম' to "m",
            'য' to "j", 'র' to "r", 'ল' to "l", 'শ' to "sh", 'ষ' to "sh", 'স' to "s",
            'হ' to "h", '\u09DC' to "r", '\u09DD' to "r", '\u09DF' to "y", 'ং' to "ng"
        )
        val sb = StringBuilder()
        for (ch in normalized) {
            val mapped = map[ch]
            if (mapped != null) sb.append(mapped)
            else if (ch in 'a'..'z' || ch in '0'..'9' || ch == ' ') sb.append(ch)
        }
        return sb.toString()
    }
}
